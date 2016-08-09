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

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Scorer;
import org.dmg.pmml.Model;
import org.dmg.pmml.PMML;
import org.dmg.pmml.RegressionModel;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.plugin.TokenPlugin;
import org.elasticsearch.script.CompiledScript;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.LeafSearchScript;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.script.modelinput.DataSource;
import org.elasticsearch.script.modelinput.EsDataSource;
import org.elasticsearch.script.modelinput.VectorRangesToVectorJSON;
import org.elasticsearch.script.models.EsLinearSVMModel;
import org.elasticsearch.script.models.EsLogisticRegressionModel;
import org.elasticsearch.script.models.EsModelEvaluator;
import org.elasticsearch.script.models.ModelInput;
import org.elasticsearch.script.models.ModelInputEvaluator;
import org.elasticsearch.search.lookup.LeafDocLookup;
import org.elasticsearch.search.lookup.LeafIndexLookup;
import org.elasticsearch.search.lookup.LeafSearchLookup;
import org.elasticsearch.search.lookup.SearchLookup;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.util.Map;

/**
 * Provides the infrastructure for Groovy as a scripting language for Elasticsearch
 */
public class PMMLModelScriptEngineService extends AbstractComponent implements ScriptEngineService {

    public static final String NAME = "pmml_model";

    public static final PMMLParser parser = PMMLParser.createDefaultPMMLParser();

    @Inject
    public PMMLModelScriptEngineService(Settings settings) {
        super(settings);
    }

    @Override
    public void close() {
    }

    @Override
    public String getType() {
        return NAME;
    }

    @Override
    public String getExtension() {
        return NAME;
    }

    @Override
    public Object compile(String scriptName, String scriptSource, Map<String, String> params) {
        return new Factory<>(scriptSource);
    }

    @Override
    public ExecutableScript executable(CompiledScript compiledScript, @Nullable Map<String, Object> vars) {
        throw new UnsupportedOperationException("model script not supported in this context!");
    }

    public class Factory<T extends ModelInput> {
        public static final String VECTOR_MODEL_DELIMITER = "dont know what to put here";

        public EsModelEvaluator<T> getModel() {
            return model;
        }

        ModelInputEvaluator<T> features = null;

        private EsModelEvaluator<T> model;

        @SuppressWarnings("unchecked")
        public Factory(String spec) {
            if (spec.contains(VECTOR_MODEL_DELIMITER)) {
                // In case someone pulled the vectors from elasticsearch the the vector spec is stored in the same script
                // as the model but as a json string
                // this is a clumsy workaround which we probably should remove at some point.
                // Would be much better if we figure out TextIndex in PMML:
                // http://dmg.org/pmml/v4-2-1/Transformations.html#xsdElement_TextIndex
                // or we remove the ability to pull vectors from elasticsearch via this plugin altogether...

                // split into vector and model
                String[] vectorAndModel = spec.split(VECTOR_MODEL_DELIMITER);
                Map<String, Object> parsedSource = null;
                try {
                    XContentParser parser = XContentFactory.xContent(XContentType.JSON).createParser(vectorAndModel[0]);
                    parsedSource = parser.mapOrdered();
                } catch (IOException e) {
                    throw new IllegalArgumentException("pmml prediction failed", e);
                }
                features = (ModelInputEvaluator<T>) new VectorRangesToVectorJSON(parsedSource);
                if (model == null) {
                    try {
                        model = initModelWithoutPreProcessing(vectorAndModel[1]);
                    } catch (SAXException | JAXBException | IOException e) {
                        throw new IllegalArgumentException("pmml prediction failed", e);
                    }
                }
            } else {
                ModelAndInputEvaluator<T> fieldsToVectorAndModel = initFeaturesAndModelFromFullPMMLSpec(spec);
                features = fieldsToVectorAndModel.vectorRangesToVector;
                model = fieldsToVectorAndModel.model;
            }
        }

        private ModelAndInputEvaluator<T> initFeaturesAndModelFromFullPMMLSpec(final String pmmlString) {

            PMML pmml = ProcessPMMLHelper.parsePmml(pmmlString);
            if (pmml.getModels().size() > 1) {
                throw new UnsupportedOperationException("Only implemented PMML for one model so far.");
            }
            return getFeaturesAndModelFromFullPMMLSpec(pmml, 0);

        }

        public EsModelEvaluator<T> initModelWithoutPreProcessing(final String pmmlString) throws IOException,
                SAXException,
                JAXBException {
            // this is bad but I have not figured out yet how to avoid the permission for suppressAccessCheck
            PMML pmml = ProcessPMMLHelper.parsePmml(pmmlString);
            Model model = pmml.getModels().get(0);
            if (model.getModelName().equals("logistic regression")) {
                return initLogisticRegression((RegressionModel) model);
            } else if (model.getModelName().equals("linear SVM")) {
                return initLinearSVM((RegressionModel) model);
            } else {
                throw new UnsupportedOperationException("We only implemented logistic regression so far but your model is of type " +
                        model.getModelName());
            }

        }

        @SuppressWarnings("unchecked")
        protected EsModelEvaluator<T> initLogisticRegression(RegressionModel pmmlModel) {
            return (EsModelEvaluator<T>)new EsLogisticRegressionModel(pmmlModel);
        }

        @SuppressWarnings("unchecked")
        protected EsModelEvaluator<T> initLinearSVM(RegressionModel pmmlModel) {
            return (EsModelEvaluator<T>)new EsLinearSVMModel(pmmlModel);
        }


        public PMMLModel<T> newScript(LeafSearchLookup lookup, boolean debug) {
            return new PMMLModel<>(features, model, lookup, debug);
        }
    }

    public <T extends ModelInput> ModelAndInputEvaluator<T> getFeaturesAndModelFromFullPMMLSpec(PMML pmml, int modelNum) {
        return parser.parse(pmml, modelNum);
    }

    @SuppressWarnings({"unchecked"})
    @Override
    public SearchScript search(final CompiledScript compiledScript, final SearchLookup lookup, @Nullable final Map<String, Object> vars) {
        return new SearchScript() {

            @Override
            public LeafSearchScript getLeafSearchScript(LeafReaderContext context) throws IOException {
                final LeafSearchLookup leafLookup = lookup.getLeafSearchLookup(context);
                boolean debug = true;
                if (vars.containsKey("debug")) {
                    debug = (Boolean)vars.get("debug");
                }
                PMMLModel<? extends ModelInput> scriptObject = ((Factory) compiledScript.compiled()).newScript(leafLookup, debug);
                return scriptObject;
            }

            @Override
            public boolean needsScores() {
                // TODO: can we reliably know if a vectorizer script does not make use of _score
                return false;
            }
        };
    }

    public static class PMMLModel<T extends ModelInput> implements LeafSearchScript {
        EsModelEvaluator<T> model = null;
        private boolean debug;
        private final ModelInputEvaluator<T> features;
        private LeafSearchLookup lookup;
        private DataSource dataSource;

        /**
         * Factory that is registered in
         * {@link TokenPlugin#onModule(ScriptModule)}
         * method when the plugin is loaded.
         */

        private PMMLModel(ModelInputEvaluator<T> features, EsModelEvaluator<T> model, LeafSearchLookup lookup, boolean debug) {
            this.dataSource = new EsDataSource() {
                @Override
                protected LeafDocLookup getDocLookup() {
                    return lookup.doc();
                }

                @Override
                protected LeafIndexLookup getLeafIndexLookup() {
                    return lookup.indexLookup();
                }
            };
            this.lookup = lookup;
            this.features = features;
            this.model = model;
            this.debug = debug;
        }

        @Override
        public void setNextVar(String s, Object o) {
        }

        @SuppressWarnings("unchecked")
        @Override
        public Object run() {
            T vector = features.convert(dataSource);
            if (debug) {
                return model.evaluateDebug(vector);
            } else {
                return model.evaluate(vector);
            }
        }

        @Override
        public Object unwrap(Object o) {
            return o;
        }

        @Override
        public void setDocument(int i) {
            if (lookup != null) {
                lookup.setDocument(i);
            }
        }

        @Override
        public void setSource(Map<String, Object> map) {
            if (lookup != null) {
                lookup.source().setSource(map);
            }
        }

        @Override
        public long runAsLong() {
            throw new UnsupportedOperationException("model script not supported in this context!");
        }

        @Override
        public double runAsDouble() {
            throw new UnsupportedOperationException("model script not supported in this context!");
        }

        @Override
        public void setScorer(Scorer scorer) {

        }
    }
}

