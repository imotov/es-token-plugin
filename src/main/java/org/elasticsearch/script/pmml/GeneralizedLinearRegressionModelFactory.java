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

package org.elasticsearch.script.pmml;

import org.dmg.pmml.DataDictionary;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DerivedField;
import org.dmg.pmml.FieldUsageType;
import org.dmg.pmml.GeneralRegressionModel;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.OpType;
import org.dmg.pmml.PCell;
import org.dmg.pmml.PPCell;
import org.dmg.pmml.Parameter;
import org.dmg.pmml.Predictor;
import org.dmg.pmml.TransformationDictionary;
import org.dmg.pmml.Value;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.script.modelinput.PMMLVectorRange;
import org.elasticsearch.script.modelinput.VectorModelInput;
import org.elasticsearch.script.modelinput.VectorModelInputEvaluator;
import org.elasticsearch.script.modelinput.VectorRange;
import org.elasticsearch.script.modelinput.VectorRangesToVectorPMML;
import org.elasticsearch.script.models.EsLogisticRegressionModel;
import org.elasticsearch.script.modelinput.ModelAndModelInputEvaluator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


public class GeneralizedLinearRegressionModelFactory extends ModelFactory<VectorModelInput, String, GeneralRegressionModel> {

    public GeneralizedLinearRegressionModelFactory() {
        super(GeneralRegressionModel.class);
    }

    private PMMLVectorRange getFieldVector(List<PPCell> cells, int indexCounter, List<DerivedField> derivedFields, DataField rawField,
                                           MiningField miningField) {
        PMMLVectorRange featureEntries;
        OpType opType;
        if (derivedFields.size() == 0) {
            opType = rawField.getOpType();
        } else {
            opType = derivedFields.get(0).getOpType();
        }

        if (opType.value().equals("continuous")) {
            featureEntries = new PMMLVectorRange.ContinousSingleEntryVectorRange(rawField, miningField, derivedFields.toArray(new
                    DerivedField[derivedFields
                    .size()]));
        } else if (opType.value().equals("categorical")) {
            featureEntries = new PMMLVectorRange.SparseCategoricalVectorRange(rawField, miningField, derivedFields.toArray(new
                    DerivedField[derivedFields
                    .size()]));
        } else {
            throw new UnsupportedOperationException("Only implemented continuous and categorical variables so far.");
        }

        for (PPCell cell : cells) {
            featureEntries.addVectorEntry(indexCounter, cell.getValue());
            indexCounter++;
        }
        return featureEntries;
    }

    private PMMLVectorRange getFeatureEntryFromGeneralRegressionModel(GeneralRegressionModel grModel,
                                                                      TransformationDictionary transformationDictionary,
                                                                      DataDictionary dataDictionary,
                                                                      String fieldName, List<PPCell> cells,
                                                                      int indexCounter) {
        List<DerivedField> allDerivedFields = ProcessPMMLHelper.getAllDerivedFields(grModel, transformationDictionary);
        List<DerivedField> derivedFields = new ArrayList<>();
        String rawFieldName = ProcessPMMLHelper.getDerivedFields(fieldName, allDerivedFields, derivedFields);
        DataField rawField = ProcessPMMLHelper.getRawDataField(dataDictionary, rawFieldName);
        MiningField miningField = ProcessPMMLHelper.getMiningField(grModel, rawFieldName);

        PMMLVectorRange featureEntries = getFieldVector(cells, indexCounter, derivedFields, rawField, miningField);
        return featureEntries;
    }

    private List<VectorRange> convertToFeatureEntries(GeneralRegressionModel grModel,
                                                      TransformationDictionary transformationDictionary,
                                                      DataDictionary dataDictionary, TreeMap<String, List<PPCell>> fieldToPPCellMap,
                                                      List<String> orderedParameterList) {
        // for each predictor: get vector entries?
        List<VectorRange> vectorRangeList = new ArrayList<>();
        int indexCounter = 0;
        // for each of the fields create the feature entries
        for (String fieldname : fieldToPPCellMap.keySet()) {
            PMMLVectorRange featureEntries = getFeatureEntryFromGeneralRegressionModel(grModel, transformationDictionary,
                    dataDictionary, fieldname, fieldToPPCellMap.get(fieldname), indexCounter);
            for (PPCell cell : fieldToPPCellMap.get(fieldname)) {
                orderedParameterList.add(cell.getParameterName());
            }
            indexCounter += featureEntries.size();
            vectorRangeList.add(featureEntries);

        }
        return vectorRangeList;
    }

    private TreeMap<String, List<PPCell>> mapCellsToFields(GeneralRegressionModel grModel) {

        // check that correlation matrix only has one entry per parameter
        // we nned to implement correlations later, see http://dmg.org/pmml/v4-2-1/GeneralRegression.html (PPMatrix) but not now...
        Set<String> parametersInPPMatrix = new HashSet<>();
        for (PPCell ppcell : grModel.getPPMatrix().getPPCells()) {
            if (parametersInPPMatrix.contains(ppcell.getParameterName())) {
                throw new UnsupportedOperationException("Don't support correlated predictors for GeneralRegressionModel yet");
            } else {
                parametersInPPMatrix.add(ppcell.getParameterName());
            }
        }

        //get all the field names for multinomialLogistic model
        TreeMap<String, List<PPCell>> fieldToPPCellMap = new TreeMap<>();
        for (Predictor predictor : grModel.getFactorList().getPredictors()) {
            fieldToPPCellMap.put(predictor.getName().toString(), new ArrayList<PPCell>());
        }
        for (Predictor predictor : grModel.getCovariateList().getPredictors()) {
            fieldToPPCellMap.put(predictor.getName().toString(), new ArrayList<PPCell>());
        }

        // get all the entries and sort them by field.
        // then create one feature entry per field and add them to vectorRangesToVector.
        // also we must keep a list of parameter names here to make sure the model uses the same order!

        for (PPCell ppcell : grModel.getPPMatrix().getPPCells()) {
            fieldToPPCellMap.get(ppcell.getField().toString()).add(ppcell);
        }
        return fieldToPPCellMap;
    }

    private void addIntercept(GeneralRegressionModel grModel, List<VectorRange> vectorRangeMap, Map<String, List<PPCell>>
            fieldToPPCellMap, List<String> orderedParameterList) {
        // now, find the order of vector entries to model parameters. This is extremely annoying but we have to do it at some
        // point...
        int numFeatures = 0; // current index?
        Set<String> allFieldParameters = new HashSet<>();
        for (Map.Entry<String, List<PPCell>> fieldAndCells : fieldToPPCellMap.entrySet()) {
            for (PPCell cell : fieldAndCells.getValue()) {
                allFieldParameters.add(cell.getParameterName());
                numFeatures++;
            }
        }
        // now find the parameters which do not come form a field
        for (Parameter parameter : grModel.getParameterList().getParameters()) {
            if (allFieldParameters.contains(parameter.getName()) == false) {
                PMMLVectorRange.Intercept intercept = new PMMLVectorRange.Intercept(parameter.getName(), "double");
                intercept.addVectorEntry(numFeatures, null);
                numFeatures++;
                vectorRangeMap.add(intercept);
                orderedParameterList.add(parameter.getName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public ModelAndModelInputEvaluator<VectorModelInput, String> buildFromPMML(GeneralRegressionModel grModel,
                                                                               DataDictionary dataDictionary,
                                                                               TransformationDictionary transformationDictionary) {
        if (grModel.getFunctionName().value().equals("classification") && (grModel.getModelType().value().equals
                ("multinomialLogistic") || (grModel.getModelType().value().equals
                ("generalizedLinear") && grModel.getDistribution().value().equals("binomial") && grModel.getLinkFunction().value().equals
                ("logit")))) {
            TreeMap<String, List<PPCell>> fieldToPPCellMap = mapCellsToFields(grModel);
            // this list stores the order of the parameters as the vectors will return them. So, if p5 is a parameter that has index 11
            // in the vetor then this parameter will be at position 11 in the ordered parameter list.
            List<String> orderedParameterList = new ArrayList<>();
            List<VectorRange> vectorRangeList = convertToFeatureEntries(grModel, transformationDictionary, dataDictionary,
                    fieldToPPCellMap, orderedParameterList);
            //add intercept if any
            addIntercept(grModel, vectorRangeList, fieldToPPCellMap, orderedParameterList);

            assert orderedParameterList.size() == grModel.getParameterList().getParameters().size();
            VectorModelInputEvaluator vectorEntries = new VectorModelInputEvaluator(vectorRangeList);

            // now finally create the model!
            // find all the coefficients for each class
            // first: sort all by target class
            Map<String, List<PCell>> targetClassPCellMap = mapParametersToTargetCategory(grModel);

            if (targetClassPCellMap.size() != 1) {
                throw new UnsupportedOperationException("We do not support more than two classes for GeneralizedRegression for " +
                        "classification");
            }

            double[] coefficients = getGLMCoefficients(orderedParameterList, targetClassPCellMap);

            //get the target class values. one we can get from the Pmatrix but the other one we have to find in the data dictionary
            String targetVariable = findTargetVariableName(grModel);

            // this need to be more if we implement more than two class
            String[] targetCategories = findTargetCategories(dataDictionary, targetClassPCellMap, targetVariable);
            EsLogisticRegressionModel logisticRegressionModel = new EsLogisticRegressionModel(coefficients, 0.0, targetCategories);
            return new ModelAndModelInputEvaluator<>(vectorEntries, logisticRegressionModel);

        } else {
            throw new UnsupportedOperationException("Only implemented logistic regression with multinomialLogistic so far.");
        }
    }

    private Map<String, List<PCell>> mapParametersToTargetCategory(GeneralRegressionModel grModel) {
        Map<String, List<PCell>> targetClassPCellMap = new HashMap<>();
        for (PCell pCell : grModel.getParamMatrix().getPCells()) {
            String targetClassName = pCell.getTargetCategory();
            if (targetClassPCellMap.containsKey(targetClassName) == false) {
                targetClassPCellMap.put(targetClassName, new ArrayList<PCell>());
            }
            targetClassPCellMap.get(targetClassName).add(pCell);
        }
        return targetClassPCellMap;
    }

    private String[] findTargetCategories(DataDictionary dataDictionary, Map<String, List<PCell>> targetClassPCellMap,
                                          String targetVariable) {
        String[] targetCategories = new String[2];
        String class1 = targetClassPCellMap.keySet().iterator().next();
        targetCategories[0] = class1;
        // find it in the datafields
        for (DataField dataField : dataDictionary.getDataFields()) {
            if (dataField.getName().toString().equals(targetVariable)) {
                for (Value value : dataField.getValues()) {
                    String valueString = value.getValue();
                    if (valueString.equals(class1) == false) {
                        targetCategories[1] = valueString;
                    }
                }
                if (targetCategories[1] == null) {
                    throw new ElasticsearchParseException("could not find target class");
                }
                break;
            }
        }
        return targetCategories;
    }

    private String findTargetVariableName(GeneralRegressionModel grModel) {
        String targetVariable = null;
        for (MiningField miningField : grModel.getMiningSchema().getMiningFields()) {
            FieldUsageType fieldUsageType = miningField.getUsageType();
            if (fieldUsageType != null && fieldUsageType.value().equals("target")) {
                targetVariable = miningField.getName().getValue();
                break;
            }
        }
        if (targetVariable == null) {
            // fall back to find the "predicted" field
            for (MiningField miningField : grModel.getMiningSchema().getMiningFields()) {
                FieldUsageType fieldUsageType = miningField.getUsageType();
                if (fieldUsageType != null && fieldUsageType.value().equals("predicted")) {
                    targetVariable = miningField.getName().getValue();
                    break;
                }
            }
        }
        if (targetVariable == null) {
            throw new ElasticsearchParseException("could not find target variable");
        }
        return targetVariable;
    }

    // get the model coefficients ad return them in the order defined by orderedParameterList
    private double[] getGLMCoefficients(List<String> orderedParameterList, Map<String, List<PCell>> targetClassPCellMap) {
        List<PCell> coefficientCells = targetClassPCellMap.values().iterator().next();
        if (coefficientCells.size() > orderedParameterList.size()) {
            throw new ElasticsearchParseException("Parameter list contains more entries than parameters");
        }
        double[] coefficients = new double[orderedParameterList.size()];
        Arrays.fill(coefficients, 0.0);
        for (int i = 0; i < coefficients.length; i++) {
            String parameter = orderedParameterList.get(i);
            for (PCell pCell : coefficientCells) {
                if (pCell.getParameterName().equals(parameter)) {
                    coefficients[i] = pCell.getBeta();
                    // TODO: what to do with df? I don't get the documentation: http://dmg.org/pmml/v4-2-1/GeneralRegression.html
                }
            }
        }
        return coefficients;
    }
}
