/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.script;

import org.dmg.pmml.*;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.search.lookup.LeafDocLookup;
import org.elasticsearch.search.lookup.LeafFieldsLookup;
import org.elasticsearch.search.lookup.LeafIndexLookup;

import java.util.HashMap;
import java.util.Map;

public abstract class PMMLFeatureEntries extends FeatureEntries {

    protected PreProcessingStep[] preProcessingSteps;


    protected Object applyPreProcessing(Object value) {
        for (int i = 0; i < preProcessingSteps.length; i++) {
            value = preProcessingSteps[i].apply(value);
        }
        return value;
    }


    public abstract void addVectorEntry(int indexCounter, PPCell ppcell);

    /**
     * Converts a 1 of k feature into a vector that has a 1 where the field value is the nth category and 0 everywhere else.
     * Categories will be numbered according to the order given in categories parameter.
     */
    public static class SparseCategorical1OfKFeatureEntries extends PMMLFeatureEntries {
        Map<String, Integer> categoryToIndexHashMap = new HashMap<>();

        public SparseCategorical1OfKFeatureEntries(DataField dataField, DerivedField[] derivedFields) {
            this.field = dataField.getName().getValue();
            preProcessingSteps = new PreProcessingStep[derivedFields.length];
            fillPreProcessingSteps(derivedFields);
        }

        @Override
        public EsVector getVector(LeafDocLookup docLookup, LeafFieldsLookup fieldsLookup, LeafIndexLookup leafIndexLookup) {
            Tuple<int[], double[]> indicesAndValues;
            Object category = docLookup.get(field);
            Object processedCategory = applyPreProcessing(category);
            int index = categoryToIndexHashMap.get(processedCategory);
            indicesAndValues = new Tuple<>(new int[]{index}, new double[]{1.0});
            return new EsSparseVector(indicesAndValues);
        }

        @Override
        public void addVectorEntry(int indexCounter, PPCell ppcell) {
            categoryToIndexHashMap.put(ppcell.getValue(), indexCounter);
        }

        @Override
        public int size() {
            return categoryToIndexHashMap.size();
        }

    }

    /**
     * Converts a 1 of k feature into a vector that has a 1 where the field value is the nth category and 0 everywhere else.
     * Categories will be numbered according to the order given in categories parameter.
     */
    public static class ContinousSingleEntryFeatureEntries extends PMMLFeatureEntries {
        int index = -1;

        /**
         * The derived fields must be given in backwards order of the processing chain.
         */
        public ContinousSingleEntryFeatureEntries(DataField dataField, DerivedField... derivedFields) {
            this.field = dataField.getName().getValue();
            preProcessingSteps = new PreProcessingStep[derivedFields.length];
            fillPreProcessingSteps(derivedFields);

        }

        @Override
        public EsVector getVector(LeafDocLookup docLookup, LeafFieldsLookup fieldsLookup, LeafIndexLookup leafIndexLookup) {
            Tuple<int[], double[]> indicesAndValues;
            Object value = docLookup.get(field);
            value = applyPreProcessing(value);
            indicesAndValues = new Tuple<>(new int[]{index}, new double[]{((Number) value).doubleValue()});
            return new EsSparseVector(indicesAndValues);
        }

        @Override
        public void addVectorEntry(int indexCounter, PPCell ppcell) {
            index = indexCounter;
        }

        @Override
        public int size() {
            return 1;
        }


    }

    protected void fillPreProcessingSteps(DerivedField[] derivedFields) {
        for (int i = derivedFields.length - 1; i >= 0; i--) {
            DerivedField derivedField = derivedFields[i];
            if (derivedField.getExpression() != null) {
                if (derivedField.getExpression() instanceof Apply) {
                    for (Expression expression : ((Apply) derivedField.getExpression()).getExpressions()) {
                        if (expression instanceof Apply) {
                            if (((Apply) expression).getFunction().equals("isMissing")) {
                                // now find the value that is supposed to replace the missing value

                                for (Expression expression2 : ((Apply) derivedField.getExpression()).getExpressions()) {
                                    if (expression2 instanceof Constant) {
                                        String missingValue = ((Constant) expression2).getValue();
                                        Object parsedMissingValue;
                                        if (derivedField.getDataType().equals(DataType.DOUBLE)) {
                                            parsedMissingValue = Double.parseDouble(missingValue);
                                        } else if (derivedField.getDataType().equals(DataType.FLOAT)) {
                                            parsedMissingValue = Float.parseFloat(missingValue);
                                        } else if (derivedField.getDataType().equals(DataType.INTEGER)) {
                                            parsedMissingValue = Integer.parseInt(missingValue);
                                        } else if (derivedField.getDataType().equals(DataType.STRING)) {
                                            parsedMissingValue = missingValue;
                                        } else {
                                            throw new UnsupportedOperationException("Only implemented data type double, float and int so " +
                                                    "far.");
                                        }
                                        preProcessingSteps[i] = new MissingValue(parsedMissingValue);
                                        break;
                                    }
                                }
                            } else {
                                throw new UnsupportedOperationException("So far only if isMissing implemented.");
                            }
                        }
                    }
                } else {
                    throw new UnsupportedOperationException("So far only Apply expression implemented.");
                }
            } else {
                throw new UnsupportedOperationException("So far only Apply implemented.");
            }
        }
    }
}
