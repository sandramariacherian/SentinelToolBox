/*
 * Copyright (C) 2013 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.pfa.activelearning;

import libsvm.svm_model;
import org.esa.pfa.fe.op.Feature;
import org.esa.pfa.fe.op.Patch;

import java.util.*;

/**
 * Active learning class.
 *
 * [1] Begum Demir and Lorenzo Bruzzone, "An effective active learning method for interactive content-based retrieval
 *     in remote sensing images", Geoscience and Remote Sensing Symposium (IGARSS), 2013 IEEE International.
 */

public class ActiveLearning {

    private int h = 0; // number of batch samples selected with diversity and density criteria
    private int q = 0; // number of uncertainty samples selected with uncertainty criterion
    private int iteration = 0;  // Iteration index in active learning
    private List<Patch> testData = new ArrayList<Patch>();
    private List<Patch> trainingData = new ArrayList<Patch>();
    private List<Patch> uncertainSamples = new ArrayList<Patch>();
    private List<Patch> diverseSamples = new ArrayList<Patch>();
    private SVM svmClassifier = null;

    private static int numInitialIterations = 3; // AL parameter
    private static int maxIterationsKmeans = 10; // KKC parameter
    private static int numFolds = 5;    // SVM parameter: number of folds for cross validation
    private static double lower = 0.0;  // SVM parameter: training data scaling lower limit
    private static double upper = 1.0;  // SVM parameter: training data scaling upper limit

    private boolean debug = false;

    public ActiveLearning() throws Exception {
        svmClassifier = new SVM(numFolds, lower, upper);
    }

    /**
     * Set patches obtained from user's query image. These patch forms the initial training data set.
     * @param patchArray The patch array.
     * @throws Exception The exception.
     */
    public void setQueryPatches(Patch[] patchArray) throws Exception {

        iteration = 0;
        trainingData.clear();
        checkQueryPatchesValidation(patchArray);
        trainingData.addAll(Arrays.asList(patchArray));

        if (debug) {
            System.out.println("Number of patches from query image: " + patchArray.length);
        }
    }

    /**
     * Set random patches obtained from archive. These patches will be used in active learning.
     * @param patchArray The patch array.
     * @throws Exception The exception.
     */
    public void setRandomPatches(Patch[] patchArray) throws Exception {

        setTestDataSetWithValidPatches(patchArray);

        setInitialTrainingSet();

        svmClassifier.train(trainingData);

        if (debug) {
            System.out.println("Number of random patches: " + patchArray.length);
            System.out.println("Number of patches in test data pool: " + testData.size());
            System.out.println("Number of patches in training data set: " + trainingData.size());
        }
    }

    /**
     * Get the most ambiguous patches selected by the active learning algorithm.
     * @param numImages The number of ambiguous patches.
     * @return The patch array.
     * @throws Exception The exceptions.
     */
    public Patch[] getMostAmbiguousPatches(int numImages) throws Exception {

        this.h = numImages;
        this.q = 4 * h;
        if (debug) {
            System.out.println("Number of uncertain patches to select: " + q);
            System.out.println("Number of diverse patches to select: " + h);
        }

        selectMostUncertainSamples();

        selectMostDiverseSamples();

        if (debug) {
            for (Patch patch:diverseSamples) {
                System.out.println("Ambiguous patch: x" + patch.getPatchX() + "y" + patch.getPatchY());
            }
        }

        return diverseSamples.toArray(new Patch[diverseSamples.size()]);
    }

    /**
     * Update training set with user labeled patches and train the classifier.
     * @param userLabelledPatches The user labeled patch array.
     * @throws Exception The exception.
     */
    public void train(Patch[] userLabelledPatches) throws Exception {

        checkLabels(userLabelledPatches);

        trainingData.addAll(Arrays.asList(userLabelledPatches));

        svmClassifier.train(trainingData);

        iteration++;

        if (debug) {
            System.out.println("Number of patches in training data set: " + trainingData.size());
        }
    }

    /**
     * Classify an array of patches. UI needs to sort the patches according to their distances to hyperplane.
     * @param patchArray The Given patch array.
     * @throws Exception The exception.
     */
    public void classify(Patch[] patchArray) throws Exception {

        final double[] decValues = new double[1];
        for (Patch patch : patchArray) {
            double p = svmClassifier.classify(patch, decValues);
            final int label = p < 1 ? Patch.LABEL_IRRELEVANT : Patch.LABEL_RELEVANT;
            patch.setLabel(label);
            patch.setDistance(decValues[0]);
            //System.out.println("Classified patch: x" + patch.getPatchX() + "y" + patch.getPatchY() + ", label: " + label);
        }

        if (debug) {
            System.out.println("Number of patches to classify: " + patchArray.length);
        }
    }

    /**
     * Get patches in the training set.
     * @return The patch array.
     */
    public Patch[] getTrainingData() {

        return trainingData.toArray(new Patch[trainingData.size()]);
    }

    /**
     * Save training patches in a file.
     * @param fileName The file name string.
     */
    public void saveTrainingData(String fileName) {

    }

    /**
     * Load training patches saved in file and use them to initialize trainingData.
     * @param fileName The file name string.
     */
    public void loadTrainingData(String fileName) {

    }
	
    public void setModel(final svm_model model) {
        svmClassifier.setModel(model);
    }

    public svm_model getModel() {
        return svmClassifier.getModel();
    }
	
    /**
     * Save SVM model to file.
     * @param fileName The file name string.
     * @throws Exception The exception.
     */
    public void saveSVMModel(String fileName) throws Exception {

        svmClassifier.saveSVMModel(fileName);
    }

    /**
     * Load the SVM model saved in file.
     * @param fileName The file name string.
     * @throws Exception The exception.
     */
    public void loadSVMModel(String fileName) throws Exception {

        svmClassifier.loadSVMModel(fileName);
    }

    /**
     * Check validity of the query patches.
     * @param patchArray The patch array.
     * @throws Exception The exception.
     */
    private void checkQueryPatchesValidation(final Patch[] patchArray) throws Exception {

        ArrayList<Integer> classLabels = new ArrayList<Integer>();
        for (Patch patch:patchArray) {

            final int label = patch.getLabel();
            if (!classLabels.contains(label)) {
                classLabels.add(label);
            }

            Feature[] features = patch.getFeatures();
            for (Feature f:features) {
                if (Double.isNaN(Double.parseDouble(f.getValue().toString()))) {
                    throw new Exception("Found invalid feature in query patch.");
                }
            }
        }

        if (classLabels.size() > 1) {
            throw new Exception("Found different labels in query patches.");
        }
    }

    /**
     * Set test data set with valid random patches.
     * @param patchArray The patch array.
     */
    private void setTestDataSetWithValidPatches(final Patch[] patchArray) {

        int count = 0;
        for (Patch patch:patchArray) {
            Feature[] features = patch.getFeatures();
            boolean isValid = true;
            for (Feature f:features) {
                if (Double.isNaN(Double.parseDouble(f.getValue().toString()))) {
                    isValid = false;
                    count++;
                    break;
                }
            }

            if (isValid) {
                testData.add(patch);
            }
        }

        if (debug) {
            System.out.println("Number of invalid random patches: " + count);
        }
    }

    /**
     * Set initial training data set with relevant patches from query image and irrelevant patches from random patches.
     * Patches in random patch set that are not close to the query patches are considered as irrelevant patches.
     * Euclidean space distance is used in measuring the distance between patches.
     */
    private void setInitialTrainingSet() {

        final double[] relevantPatchClusterCenter = computeClusterCenter(trainingData);
        final double[][] distance = computeDistanceToClusterCenter(relevantPatchClusterCenter);

        java.util.Arrays.sort(distance, new java.util.Comparator<double[]>() {
            public int compare(double[] a, double[] b) {
                return Double.compare(b[1], a[1]);
            }
        });

        final int numIrrelevantSample = Math.min(10, distance.length);
        int[] patchIDs = new int[numIrrelevantSample];
        for (int i = 0; i < numIrrelevantSample; i++) {
            final Patch patch = testData.get((int)distance[i][0]);
            patch.setLabel(Patch.LABEL_IRRELEVANT);
            patchIDs[i] = patch.getID();
            trainingData.add(patch);
        }

        for (Iterator<Patch> itr = testData.iterator(); itr.hasNext(); ) {
            Patch patch = itr.next();
            for (int patchID:patchIDs) {
                if (patch.getID() == patchID) {
                    itr.remove();
                    break;
                }
            }
        }
    }

    /**
     * Compute center of the given list of patches.
     * @param patchList The patch list.
     * @return The center.
     */
    private static double[] computeClusterCenter(final List<Patch> patchList) {

        double[] center = new double[patchList.get(0).getFeatures().length];
        for (Patch patch:patchList) {
            Feature[] features = patch.getFeatures();
            for (int i = 0; i < features.length; i++) {
                center[i] += Double.parseDouble(features[i].getValue().toString());
            }
        }

        for (int i = 0; i < center.length; i++) {
            center[i] /= patchList.size();
        }

        return center;
    }

    /**
     * Compute for all samples the Euclidean distance to the center of the cluster.
     * @param clusterCenter The cluster center.
     * @return The distance array.
     */
    private double[][] computeDistanceToClusterCenter(final double[] clusterCenter) {

        final double[][] distance = new double[testData.size()][2];
        int k = 0;
        for (int i = 0; i < testData.size(); i++) {
            distance[k][0] = i; // sample index in testData
            final Feature[] features = testData.get(i).getFeatures();
            double sum = 0.0;
            for (int j = 0; j < features.length; j++) {
                final double x = Double.parseDouble(features[j].getValue().toString());
                sum += (x - clusterCenter[j])*(x - clusterCenter[j]);
            }
            distance[k][1] = sum;
            k++;
        }

        return distance;
    }

    /**
     * Check if there is any unlabeled patch.
     * @param patchArray Patch array.
     * @throws Exception The exception.
     */
    private void checkLabels(Patch[] patchArray) throws Exception {

        for (Patch patch:patchArray) {
            if (patch.getLabel() == Patch.LABEL_NONE) {
                throw new Exception("Found unlabeled patch(s)");
            }
        }
    }

    /**
     * Select uncertain samples from test data.
     * @throws Exception The exception.
     */
    private void selectMostUncertainSamples() throws Exception {

        final double[][] distance = computeFunctionalDistanceForAllSamples();

        if (iteration < numInitialIterations) {
            getAllUncertainSamples(distance);
            if (uncertainSamples.size() < q) {
                getMostUncertainSamples(distance);
            }
        } else {
            getMostUncertainSamples(distance);
        }

        if (debug) {
            System.out.println("Number of uncertain patches selected: " + uncertainSamples.size());
        }
    }

    /**
     * Compute functional distance for all samples in test data set.
     * @return The distance array.
     * @throws Exception The exception.
     */
    private double[][] computeFunctionalDistanceForAllSamples() throws Exception {

        final double[][] distance = new double[testData.size()][2];
        int k = 0;
        for (int i = 0; i < testData.size(); i++) {
            distance[k][0] = i; // sample index in testData
            distance[k][1] = computeFunctionalDistance(testData.get(i));
            k++;
        }

        return distance;
    }

    /**
     * Compute functional distance of a given sample to the SVM hyperplane.
     * @param x The given sample.
     * @return The functional distance.
     * @throws Exception The exception.
     */
    private double computeFunctionalDistance(Patch x) throws Exception {

        final double[] decValues = new double[1];
        svmClassifier.classify(x, decValues);
        return Math.abs(decValues[0]);
    }

    /**
     * Get all uncertain samples from test data set if their functional distances are less than 1.
     * @param distance The functional distance array.
     */
    private void getAllUncertainSamples(final double[][] distance) {

        uncertainSamples.clear();
        for (int i = 0; i < testData.size(); i++) {
            if (distance[i][1] < 1.0) {
                uncertainSamples.add(testData.get((int)distance[i][0]));
            }
        }
    }

    /**
     * Get q most uncertain samples from test data set based on their functional distances.
     * @param distance The functional distance array.
     */
    private void getMostUncertainSamples(final double[][] distance) {

        java.util.Arrays.sort(distance, new java.util.Comparator<double[]>() {
            public int compare(double[] a, double[] b) {
                return Double.compare(a[1], b[1]);
            }
        });

        uncertainSamples.clear();
        final int maxUncertainSample = Math.min(q, distance.length);
        for (int i = 0; i < maxUncertainSample; i++) {
            uncertainSamples.add(testData.get((int)distance[i][0]));
        }
    }

    /**
     * Select h most diverse samples from the q most uncertain samples.
     * @throws Exception The exception.
     */
    private void selectMostDiverseSamples() throws Exception {

        KernelKmeansClusterer kkc = new KernelKmeansClusterer(maxIterationsKmeans, h, svmClassifier);
        kkc.setData(uncertainSamples);
        kkc.clustering();
        final int[] diverseSampleIDs = kkc.getRepresentatives();

        diverseSamples.clear();
        for (int patchID : diverseSampleIDs) {
            for (Iterator<Patch> itr = testData.iterator(); itr.hasNext(); ) {
                Patch patch = itr.next();
                if (patch.getID() == patchID) {
                    diverseSamples.add(patch);
                    itr.remove();
                    break;
                }
            }
        }

        if (debug) {
            System.out.println("Number of diverse patches IDs: " + diverseSampleIDs.length);
            System.out.println("Number of diverse patches selected: " + diverseSamples.size());
        }

        if (diverseSamples.size() != diverseSampleIDs.length) {
            throw new Exception("Invalid diverse patch array.");
        }
    }

}