/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinax.datamapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.impl.symbols.BallerinaClassSymbol;
import io.ballerina.compiler.api.symbols.*;
import io.ballerina.compiler.syntax.tree.*;
import io.ballerina.projects.ModuleCompilation;
import io.ballerina.projects.Module;


import java.util.*;

/**
 * Visitor to extract Record Type Structure information.
 */
public class DataMapperNodeVisitor extends NodeVisitor {
    private final HashMap<String, String> recordTypes;
    private final HashMap<String, Map<String, FunctionRecord>> clientMap;
    private SemanticModel model;

    public DataMapperNodeVisitor() {
        this.recordTypes = new HashMap<String, String>();
        this.clientMap = new HashMap<>();
    }

    public HashMap<String, String> getRecordTypes() {
        return recordTypes;
    }

    public HashMap<String, Map<String, FunctionRecord>> getClientMap() {
        return clientMap;
    }

    public void setModule(Module module) {
        ModuleCompilation compilation = module.getCompilation();
        this.model = compilation.getSemanticModel();
    }

    private String getFieldTypes(Map<String, RecordFieldSymbol> fieldSymbolMap){
        Iterator<String> iterator = fieldSymbolMap.keySet().iterator();
        Map<String, String> fieldSymbols = new HashMap<>();
        String serialized = null;
        while (iterator.hasNext()) {
            String fieldName = iterator.next();
            String fieldType = fieldSymbolMap.get(fieldName).typeDescriptor().signature();
            fieldSymbols.put(fieldName, fieldType);
        }
        try {
            serialized = new ObjectMapper().writeValueAsString(fieldSymbols);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return serialized;
    }

    @Override
    public void visit(TypeDefinitionNode typeDefinitionNode) {
        if (typeDefinitionNode.typeDescriptor().kind() == SyntaxKind.RECORD_TYPE_DESC) {
            Optional<Symbol> recordSymbolOpt = this.model.symbol(typeDefinitionNode);
            if(recordSymbolOpt.isPresent()){
                Symbol recordSymbol = recordSymbolOpt.get();
                Optional<String> recordNameOpt = recordSymbol.getName();
                if(recordNameOpt.isPresent()){
                    String recordName = recordNameOpt.get();
                    String recordSignature = recordSymbol.getModule().get().id().toString();
                    recordName = recordSignature + ":" + recordName;
                    Map<String, RecordFieldSymbol> fieldSymbolMap = ((RecordTypeSymbol) ((TypeDefinitionSymbol) recordSymbol).typeDescriptor()).fieldDescriptors();
                    String serialized = getFieldTypes(fieldSymbolMap);
                    serialized = "{\"" + recordName + "\":" + serialized + "}";
                    this.recordTypes.put(recordName, serialized);
                }
            }
        }
    }

    @Override
    public void visit(ClassDefinitionNode classDefinitionNode) {
        NodeList<Token> classTypeQualifiers = classDefinitionNode.classTypeQualifiers();
        for (Token classTypeQualifier : classTypeQualifiers) {
            if (classTypeQualifier.text().equals("client")) {
                if (model.symbol(classDefinitionNode).isPresent()) {
                    Symbol symbol = model.symbol(classDefinitionNode).get();
                    if (symbol.getName().isEmpty()) {
                        continue;
                    }
                    String clientName = symbol.getName().get();
                    Collection<MethodSymbol> methods = ((BallerinaClassSymbol) symbol).methods().values();
                    Map<String, FunctionRecord> functionMap = new HashMap<>();
                    for (MethodSymbol method : methods) {
                        boolean checkForRemote = false;
                        for (Qualifier qualifier : method.qualifiers()) {
                            if (qualifier == Qualifier.REMOTE) {
                                checkForRemote = true;
                                break;
                            }
                        }
                        if (checkForRemote) {
                            if (method.getName().isEmpty()) {
                                continue;
                            }
                            String functionName = method.getName().get();
                            FunctionRecord functionRecord = new FunctionRecord();
                            for (ParameterSymbol parameter : method.typeDescriptor().parameters()) {
                                if (parameter.getName().isPresent()) {
                                    String parameterName = parameter.getName().get();
                                    String paraType = parameter.typeDescriptor().typeKind().toString();
                                    functionRecord.addParameter(parameterName, paraType);
                                }
                            }
                            TypeSymbol returnTypeSymbol = method.typeDescriptor().returnTypeDescriptor().get();
                            String returnName = null;
                            if (returnTypeSymbol.typeKind() == TypeDescKind.UNION) {
                                List<TypeSymbol> returnList = ((UnionTypeSymbol) returnTypeSymbol).memberTypeDescriptors();
                                for (TypeSymbol typeSymbol : returnList) {
                                    if (typeSymbol.typeKind() == TypeDescKind.ERROR) {
                                        continue;
                                    } else {
                                        returnName = typeSymbol.signature();
                                        break;
                                    }
                                }
                            } else {
                                returnName = returnTypeSymbol.signature();
                            }
                            functionRecord.setReturnType(returnName);
                            functionMap.put(functionName, functionRecord);
                            checkForRemote = false;
                        }
                    }
                    if (!functionMap.isEmpty()) {
                        this.clientMap.put(clientName, functionMap);
                    }
                }
            }
        }
    }
}

class FunctionRecord{
    private HashMap<String, String> parameters;
    private String returnType;

    public FunctionRecord(){
        parameters = new HashMap<>();
        returnType = "";
    }

    public HashMap<String, String> getParameters() {
        return parameters;
    }

    public void addParameter(String name, String type){
        this.parameters.put(name, type);
    }

    public void setParameters(HashMap<String, String> parameters) {
        this.parameters = parameters;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }
}