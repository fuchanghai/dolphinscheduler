/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.plugin.task.k8s;

import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.dolphinscheduler.plugin.datasource.k8s.param.K8sConnectionParam;
import org.apache.dolphinscheduler.plugin.task.api.K8sTaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.TaskCallBack;
import org.apache.dolphinscheduler.plugin.task.api.TaskException;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.k8s.AbstractK8sTask;
import org.apache.dolphinscheduler.plugin.task.api.k8s.AbstractK8sTaskExecutor;
import org.apache.dolphinscheduler.plugin.task.api.k8s.K8sTaskMainParameters;
import org.apache.dolphinscheduler.plugin.task.api.k8s.impl.K8sTaskExecutor;
import org.apache.dolphinscheduler.plugin.task.api.k8s.impl.K8sYamlTaskExecutor;
import org.apache.dolphinscheduler.plugin.task.api.model.Label;
import org.apache.dolphinscheduler.plugin.task.api.model.NodeSelectorExpression;
import org.apache.dolphinscheduler.plugin.task.api.model.Property;
import org.apache.dolphinscheduler.plugin.task.api.model.TaskResponse;
import org.apache.dolphinscheduler.plugin.task.api.parameters.AbstractParameters;
import org.apache.dolphinscheduler.plugin.task.api.parameters.K8sTaskParameters;
import org.apache.dolphinscheduler.plugin.task.api.utils.ParameterUtils;
import org.apache.dolphinscheduler.spi.enums.DbType;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.NodeSelectorRequirement;

public class K8sTask extends AbstractK8sTask {

    /**
     * taskExecutionContext
     */
    private final TaskExecutionContext taskExecutionContext;

    /**
     * task parameters
     */
    private K8sTaskParameters k8sTaskParameters;

    private K8sTaskExecutionContext k8sTaskExecutionContext;
    /**
     * process task
     */
    private AbstractK8sTaskExecutor abstractK8sTaskExecutor;

    private K8sConnectionParam k8sConnectionParam;
    /**
     * @param taskRequest taskRequest
     */
    public K8sTask(TaskExecutionContext taskRequest) {
        super(taskRequest);
        this.taskExecutionContext = taskRequest;
    }

    @Override
    public void init() {
        String taskParams = taskExecutionContext.getTaskParams();
        k8sTaskParameters = JSONUtils.parseObject(taskParams, K8sTaskParameters.class);
        if (k8sTaskParameters == null || !k8sTaskParameters.checkParameters()) {
            throw new TaskException("K8S task params is not valid");
        }

        k8sTaskExecutionContext =
                k8sTaskParameters.generateExtendedContext(taskExecutionContext.getResourceParametersHelper());
        taskRequest.setK8sTaskExecutionContext(k8sTaskExecutionContext);
        k8sConnectionParam =
                (K8sConnectionParam) DataSourceUtils.buildConnectionParams(DbType.valueOf(k8sTaskParameters.getType()),
                        k8sTaskExecutionContext.getConnectionParams());
        String kubeConfig = k8sConnectionParam.getKubeConfig();
        k8sTaskParameters.setNamespace(k8sConnectionParam.getNamespace());
        k8sTaskParameters.setKubeConfig(kubeConfig);
        log.info("Initialize k8s task params:{}", JSONUtils.toPrettyJsonString(k8sTaskParameters));

        if(k8sTaskParameters.getCustomCofig() == 0){
            this.abstractK8sTaskExecutor = new K8sTaskExecutor(taskRequest);
        }else {
            this.abstractK8sTaskExecutor = new K8sYamlTaskExecutor(taskRequest);
        }
    }

    @Override
    public List<String> getApplicationIds() throws TaskException {
        return Collections.emptyList();
    }

    @Override
    public AbstractParameters getParameters() {
        return k8sTaskParameters;
    }


    @Override
    public void handle(TaskCallBack taskCallBack) throws TaskException {
        try {
        if(k8sTaskParameters.getCustomCofig() == 0){

                TaskResponse response = abstractK8sTaskExecutor.run(buildCommand());
                setExitStatusCode(response.getExitStatusCode());
                setAppIds(response.getAppIds());
                dealOutParam(abstractK8sTaskExecutor.getTaskOutputParams());

        }else {
            abstractK8sTaskExecutor.run(k8sTaskParameters.getYamlContent());
        }
        } catch (Exception e) {
            log.error("k8s task submit failed with error");
            exitStatusCode = -1;
            throw new TaskException("Execute k8s task error", e);
        }
    }
    @Override
    protected String buildCommand() {
        K8sTaskMainParameters k8sTaskMainParameters = new K8sTaskMainParameters();
        Map<String, Property> paramsMap = taskExecutionContext.getPrepareParamsMap();
        String namespaceName = k8sTaskParameters.getNamespace();
        k8sTaskMainParameters.setImage(k8sTaskParameters.getImage());
        k8sTaskMainParameters.setPullSecret(k8sTaskParameters.getPullSecret());
        k8sTaskMainParameters.setNamespaceName(namespaceName);
        k8sTaskMainParameters.setMinCpuCores(k8sTaskParameters.getMinCpuCores());
        k8sTaskMainParameters.setMinMemorySpace(k8sTaskParameters.getMinMemorySpace());
        k8sTaskMainParameters.setParamsMap(ParameterUtils.convert(paramsMap));
        k8sTaskMainParameters.setLabelMap(convertToLabelMap(k8sTaskParameters.getCustomizedLabels()));
        k8sTaskMainParameters
                .setNodeSelectorRequirements(convertToNodeSelectorRequirements(k8sTaskParameters.getNodeSelectors()));
        k8sTaskMainParameters.setCommand(k8sTaskParameters.getCommand());
        k8sTaskMainParameters.setArgs(k8sTaskParameters.getArgs());
        k8sTaskMainParameters.setImagePullPolicy(k8sTaskParameters.getImagePullPolicy());
        return JSONUtils.toJsonString(k8sTaskMainParameters);
    }

    @Override
    protected void dealOutParam(String result) {
        this.k8sTaskParameters.dealOutParam(result);
    }

    public List<NodeSelectorRequirement> convertToNodeSelectorRequirements(List<NodeSelectorExpression> expressions) {
        if (CollectionUtils.isEmpty(expressions)) {
            return Collections.emptyList();
        }

        return expressions.stream().map(expression -> new NodeSelectorRequirement(
                expression.getKey(),
                expression.getOperator(),
                StringUtils.isEmpty(expression.getValues()) ? Collections.emptyList()
                        : Arrays.asList(expression.getValues().trim().split("\\s*,\\s*"))))
                .collect(Collectors.toList());
    }

    public Map<String, String> convertToLabelMap(List<Label> labels) {
        if (CollectionUtils.isEmpty(labels)) {
            return Collections.emptyMap();
        }

        HashMap<String, String> labelMap = new HashMap<>();
        labels.forEach(label -> {
            labelMap.put(label.getLabel(), label.getValue());
        });
        return labelMap;
    }

}
