package org.elasticsearch.ml.training;

import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.ml.training.ModelTrainer.TrainingSession;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collection of all available model trainers
 */
public class ModelTrainers {
    private final Map<String, ModelTrainer> modelTrainers;

    public ModelTrainers(List<ModelTrainer> modelTrainers) {
        Map<String, ModelTrainer> modelParserMap = new HashMap<>();
        for (ModelTrainer trainer : modelTrainers) {
            ModelTrainer prev = modelParserMap.put(trainer.modelType(), trainer);
            if (prev != null) {
                throw new IllegalStateException("Added more than one trainer for model type" + trainer.modelType());
            }
        }
        this.modelTrainers = Collections.unmodifiableMap(modelParserMap);
    }

    public TrainingSession createTrainingSession(MappingMetaData mappingMetaData, String modelType, Settings settings,
                                                 List<ModelInputField> inputs, ModelTargetField output) {
        ModelTrainer trainer = modelTrainers.get(modelType);
        if (trainer != null) {
            return trainer.createTrainingSession(mappingMetaData, inputs, output, settings);
        } else {
            throw new UnsupportedOperationException("Unsupported model type " + modelType);
        }
    }
}
