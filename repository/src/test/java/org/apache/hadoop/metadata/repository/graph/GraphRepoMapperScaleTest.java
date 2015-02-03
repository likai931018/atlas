/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.metadata.repository.graph;

import com.google.common.collect.ImmutableList;
import com.thinkaurelius.titan.core.TitanGraph;
import com.thinkaurelius.titan.core.TitanIndexQuery;
import com.tinkerpop.blueprints.Compare;
import com.tinkerpop.blueprints.GraphQuery;
import com.tinkerpop.blueprints.Vertex;
import org.apache.hadoop.metadata.ITypedReferenceableInstance;
import org.apache.hadoop.metadata.Referenceable;
import org.apache.hadoop.metadata.RepositoryMetadataModule;
import org.apache.hadoop.metadata.Struct;
import org.apache.hadoop.metadata.types.AttributeDefinition;
import org.apache.hadoop.metadata.types.ClassType;
import org.apache.hadoop.metadata.types.DataTypes;
import org.apache.hadoop.metadata.types.EnumType;
import org.apache.hadoop.metadata.types.EnumTypeDefinition;
import org.apache.hadoop.metadata.types.EnumValue;
import org.apache.hadoop.metadata.types.HierarchicalTypeDefinition;
import org.apache.hadoop.metadata.types.IDataType;
import org.apache.hadoop.metadata.types.Multiplicity;
import org.apache.hadoop.metadata.types.StructTypeDefinition;
import org.apache.hadoop.metadata.types.TraitType;
import org.apache.hadoop.metadata.types.TypeSystem;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Map;

@Test
@Guice(modules = RepositoryMetadataModule.class)
public class GraphRepoMapperScaleTest {

    private static final String DATABASE_TYPE = "hive_database_type";
    private static final String DATABASE_NAME = "foo";
    private static final String TABLE_TYPE = "hive_table_type";
    private static final String TABLE_NAME = "bar";

    @Inject
    private TitanGraphService titanGraphService;
    @Inject
    private GraphBackedMetadataRepository repositoryService;

    private GraphBackedSearchIndexer searchIndexer;
    private TypeSystem typeSystem;
    private String dbGUID;

    @BeforeClass
    public void setUp() throws Exception {
        // start the injected graph service
        titanGraphService.start();
        // start the injected repository service
        repositoryService.start();

        searchIndexer = new GraphBackedSearchIndexer(titanGraphService);

        typeSystem = TypeSystem.getInstance();

        createHiveTypes();
    }

    @Test
    public void testSubmitEntity() throws Exception {
        Referenceable databaseInstance = new Referenceable(DATABASE_TYPE);
        databaseInstance.set("name", DATABASE_NAME);
        databaseInstance.set("description", "foo database");
        // System.out.println("databaseInstance = " + databaseInstance);

        ClassType dbType = typeSystem.getDataType(ClassType.class, DATABASE_TYPE);
        ITypedReferenceableInstance db = dbType.convert(databaseInstance, Multiplicity.REQUIRED);

        dbGUID = repositoryService.createEntity(db, DATABASE_TYPE);

        Referenceable dbInstance = new Referenceable(
                dbGUID, DATABASE_TYPE, databaseInstance.getValuesMap());

        for (int index = 0; index < 1000; index ++) {
            ITypedReferenceableInstance table = createHiveTableInstance(dbInstance, index);
            repositoryService.createEntity(table, TABLE_TYPE);
        }
    }

    @Test (dependsOnMethods = "testSubmitEntity")
    public void testSearchIndex() throws Exception {
        searchWithOutIndex(Constants.GUID_PROPERTY_KEY, dbGUID);
        searchWithOutIndex(Constants.ENTITY_TYPE_PROPERTY_KEY, "hive_column_type");
        searchWithOutIndex(Constants.ENTITY_TYPE_PROPERTY_KEY, TABLE_TYPE);

        searchWithOutIndex("hive_table_type.name", "bar-999");
        searchWithIndex("hive_table_type.name", "bar-999");

        for (int index = 500; index < 600; index ++) {
            searchWithIndex("hive_table_type.name", "bar-" + index);
        }
    }

    private void searchWithOutIndex(String key, String value) {
        TitanGraph graph = titanGraphService.getTitanGraph();
        long start = System.currentTimeMillis();
        int count = 0;
        try {
            GraphQuery query = graph.query()
                    .has(key, Compare.EQUAL, value);
            for (Vertex ignored : query.vertices()) {
                count++;
            }
        } finally {
            System.out.println("Search on [" + key + "=" + value + "] returned results: " + count
                    + ", took " + (System.currentTimeMillis() - start) + " ms");
        }
    }

    private void searchWithIndex(String key, String value) {
        TitanGraph graph = titanGraphService.getTitanGraph();
        long start = System.currentTimeMillis();
        int count = 0;
        try {
            String queryString = "v.\"" + key + "\":(" + value + ")";
            TitanIndexQuery query = graph.indexQuery(Constants.VERTEX_INDEX, queryString);
            for (TitanIndexQuery.Result<Vertex> ignored : query.vertices()) {
                count++;
            }
        } finally {
            System.out.println("Search on [" + key + "=" + value + "] returned results: " + count
                    + ", took " + (System.currentTimeMillis() - start) + " ms");
        }
    }

    private void createHiveTypes() throws Exception {
        HierarchicalTypeDefinition<ClassType> databaseTypeDefinition =
                createClassTypeDef(DATABASE_TYPE,
                        ImmutableList.<String>of(),
                        createUniqueRequiredAttrDef("name", DataTypes.STRING_TYPE),
                        createRequiredAttrDef("description", DataTypes.STRING_TYPE));

        StructTypeDefinition structTypeDefinition =
                new StructTypeDefinition("hive_serde_type",
                        new AttributeDefinition[] {
                                createRequiredAttrDef("name", DataTypes.STRING_TYPE),
                                createRequiredAttrDef("serde", DataTypes.STRING_TYPE)
                        });

        EnumValue values[] = {
                new EnumValue("MANAGED", 1),
                new EnumValue("EXTERNAL", 2),
        };

        EnumTypeDefinition enumTypeDefinition =  new EnumTypeDefinition("table_type", values);
        EnumType enumType = typeSystem.defineEnumType(enumTypeDefinition);
        searchIndexer.onAdd("table_type", enumType);

        HierarchicalTypeDefinition<ClassType> columnsDefinition =
                createClassTypeDef("hive_column_type",
                        ImmutableList.<String>of(),
                        createRequiredAttrDef("name", DataTypes.STRING_TYPE),
                        createRequiredAttrDef("type", DataTypes.STRING_TYPE));

        StructTypeDefinition partitionDefinition =
                new StructTypeDefinition("hive_partition_type",
                        new AttributeDefinition[] {
                                createRequiredAttrDef("name", DataTypes.STRING_TYPE),
                        });

        HierarchicalTypeDefinition<ClassType> tableTypeDefinition =
                createClassTypeDef(TABLE_TYPE,
                        ImmutableList.<String>of(),
                        createUniqueRequiredAttrDef("name", DataTypes.STRING_TYPE),
                        createRequiredAttrDef("description", DataTypes.STRING_TYPE),
                        createRequiredAttrDef("type", DataTypes.STRING_TYPE),
                        // enum
                        new AttributeDefinition("tableType", "table_type",
                                Multiplicity.REQUIRED, false, null),
                        // array of strings
                        new AttributeDefinition("columnNames",
                                String.format("array<%s>", DataTypes.STRING_TYPE.getName()),
                                Multiplicity.COLLECTION, false, null),
                        // array of classes
                        new AttributeDefinition("columns",
                                String.format("array<%s>", "hive_column_type"),
                                Multiplicity.COLLECTION, true, null),
                        // array of structs
                        new AttributeDefinition("partitions",
                                String.format("array<%s>", "hive_partition_type"),
                                Multiplicity.COLLECTION, true, null),
                        // struct reference
                        new AttributeDefinition("serde1",
                                "hive_serde_type", Multiplicity.REQUIRED, false, null),
                        new AttributeDefinition("serde2",
                                "hive_serde_type", Multiplicity.REQUIRED, false, null),
                        // class reference
                        new AttributeDefinition("database",
                                DATABASE_TYPE, Multiplicity.REQUIRED, true, null));

        HierarchicalTypeDefinition<TraitType> classificationTypeDefinition =
                createTraitTypeDef("pii_type", ImmutableList.<String>of());

        Map<String, IDataType> types = typeSystem.defineTypes(
                ImmutableList.of(structTypeDefinition, partitionDefinition),
                ImmutableList.of(classificationTypeDefinition),
                ImmutableList.of(databaseTypeDefinition, columnsDefinition, tableTypeDefinition));

        for (Map.Entry<String, IDataType> entry : types.entrySet()) {
            searchIndexer.onAdd(entry.getKey(), entry.getValue());
        }
    }

    private ITypedReferenceableInstance createHiveTableInstance(
            Referenceable databaseInstance, int uberIndex) throws Exception {

        Referenceable tableInstance = new Referenceable(TABLE_TYPE, "pii_type");
        tableInstance.set("name", TABLE_NAME + "-" + uberIndex);
        tableInstance.set("description", "bar table" + "-" + uberIndex);
        tableInstance.set("type", "managed");
        tableInstance.set("tableType", 1); // enum

        // refer to an existing class
        tableInstance.set("database", databaseInstance);

        ArrayList<String> columnNames = new ArrayList<>();
        columnNames.add("first_name" + "-" + uberIndex);
        columnNames.add("last_name" + "-" + uberIndex);
        tableInstance.set("columnNames", columnNames);

        Struct serde1Instance = new Struct("hive_serde_type");
        serde1Instance.set("name", "serde1" + "-" + uberIndex);
        serde1Instance.set("serde", "serde1" + "-" + uberIndex);
        tableInstance.set("serde1", serde1Instance);

        Struct serde2Instance = new Struct("hive_serde_type");
        serde2Instance.set("name", "serde2" + "-" + uberIndex);
        serde2Instance.set("serde", "serde2" + "-" + uberIndex);
        tableInstance.set("serde2", serde2Instance);

        ArrayList<Referenceable> columns = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Referenceable columnInstance = new Referenceable("hive_column_type");
            columnInstance.set("name", "column_" + "-" + uberIndex + "-" + index);
            columnInstance.set("type", "string");
            columns.add(columnInstance);
        }
        tableInstance.set("columns", columns);

        ArrayList<Struct> partitions = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Struct partitionInstance = new Struct("hive_partition_type");
            partitionInstance.set("name", "partition_" + "-" + uberIndex + "-" + index);
            partitions.add(partitionInstance);
        }
        tableInstance.set("partitions", partitions);

        ClassType tableType = typeSystem.getDataType(ClassType.class, TABLE_TYPE);
        return tableType.convert(tableInstance, Multiplicity.REQUIRED);
    }

    protected AttributeDefinition createUniqueRequiredAttrDef(String name,
                                                              IDataType dataType) {
        return new AttributeDefinition(name, dataType.getName(),
                Multiplicity.REQUIRED, false, true, true, null);
    }

    protected AttributeDefinition createRequiredAttrDef(String name,
                                                        IDataType dataType) {
        return new AttributeDefinition(name, dataType.getName(),
                Multiplicity.REQUIRED, false, null);
    }

    @SuppressWarnings("unchecked")
    protected HierarchicalTypeDefinition<TraitType> createTraitTypeDef(
            String name, ImmutableList<String> superTypes, AttributeDefinition... attrDefs) {
        return new HierarchicalTypeDefinition(TraitType.class, name, superTypes, attrDefs);
    }

    @SuppressWarnings("unchecked")
    protected HierarchicalTypeDefinition<ClassType> createClassTypeDef(
            String name, ImmutableList<String> superTypes, AttributeDefinition... attrDefs) {
        return new HierarchicalTypeDefinition(ClassType.class, name, superTypes, attrDefs);
    }
}
