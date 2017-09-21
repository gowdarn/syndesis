/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.dao;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.catalog.connector.CamelConnectorCatalog;
import org.apache.camel.catalog.connector.DefaultCamelConnectorCatalog;
import org.apache.camel.catalog.maven.DefaultMavenArtifactProvider;
import org.apache.camel.catalog.maven.MavenArtifactProvider;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Downloads all connectors defined in {@code deployment.json} and tries to
 * resolve their endpoints using Camel catalog.
 */
public class DeploymentDescriptorTest {

    private final CamelCatalog camelCatalog = new DefaultCamelCatalog(true);

    private final CamelConnectorCatalog connectorCatalog = new DefaultCamelConnectorCatalog();

    private final JsonNode deployment;

    private final ObjectMapper mapper = new ObjectMapper();

    private final MavenArtifactProvider mavenArtifactProvider = createArtifactProvider();

    public DeploymentDescriptorTest() throws IOException {
        deployment = mapper
            .readTree(DeploymentDescriptorTest.class.getResourceAsStream("/io/syndesis/dao/deployment.json"));
    }

    @Test
    @SuppressWarnings({"PMD.JUnitTestsShouldIncludeAssert", "PMD.JUnitTestContainsTooManyAsserts"})
    public void deploymentDescriptorTakeCueFromConnectorDescriptor() {
        for (final JsonNode entry : deployment) {
            if ("connector".equals(entry.get("kind").asText())) {

                final String connectorId = entry.get("data").get("id").asText();

                final JsonNode connectorData = entry.get("data");
                final JsonNode connectorPropertiesJson = connectorData.get("properties");

                final JsonNode actions = connectorData.get("actions");
                StreamSupport.stream(actions.spliterator(), true).forEach(action -> {
                    final String actionName = action.get("name").asText();
                    final String gav = action.get("camelConnectorGAV").asText();

                    assertThat(gav).as("Action `%s` does not have `camelConnectorGAV` property", actionName)
                        .isNotEmpty();

                    final String[] coordinates = gav.split(":");

                    mavenArtifactProvider.addArtifactToCatalog(camelCatalog, connectorCatalog, coordinates[0],
                        coordinates[1], coordinates[2]);

                    final String scheme = action.get("camelConnectorPrefix").asText();

                    try {
                        camelCatalog.asEndpointUri(scheme, new HashMap<>(), false);
                    } catch (final URISyntaxException e) {
                        fail("Action `%s` cannot be added to Camel context", actionName, e);
                    }

                    final String componentJsonSchemaFromCatalog = camelCatalog.componentJSonSchema(scheme);
                    final JsonNode catalogedJsonSchema;
                    try {
                        catalogedJsonSchema = mapper.readTree(componentJsonSchemaFromCatalog);
                    } catch (final IOException e) {
                        fail("Unable to parse Camel component JSON schema", e);
                        return;// never happens
                    }

                    final JsonNode componentPropertiesFromCatalog = catalogedJsonSchema.get("componentProperties");

                    assertConnectorProperties(connectorId, connectorPropertiesJson, componentPropertiesFromCatalog);

                    assertActionProperties(connectorId, action, actionName, catalogedJsonSchema);
                });
            }
        }

    }

    @Test
    public void thereShouldBeNoDuplicateMavenCoordinates() {
        final Map<String, Long> coordinatesWithCount = StreamSupport.stream(deployment.spliterator(), true)
            .filter(data -> "connector".equals(data.get("kind").asText()))
            .flatMap(connector -> StreamSupport.stream(connector.get("data").get("actions").spliterator(), true))
            .map(action -> action.get("camelConnectorGAV").asText())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        final Map<String, Long> multipleCoordinates = coordinatesWithCount.entrySet().stream()
            .filter(e -> e.getValue() > 1).collect(Collectors.toMap(Entry::getKey, Entry::getValue));

        assertThat(multipleCoordinates).as("Expected connector GAV coordinates to be unique").isEmpty();
    }

    @Test
    public void thereShouldBeNoDuplicateNames() {
        final Map<String, Long> namesWithCount = StreamSupport.stream(deployment.spliterator(), true)
            .filter(data -> "connector".equals(data.get("kind").asText()))
            .flatMap(connector -> StreamSupport.stream(connector.get("data").get("actions").spliterator(), true))
            .map(action -> action.get("name").asText())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        final Map<String, Long> multipleNames = namesWithCount.entrySet().stream().filter(e -> e.getValue() > 1)
            .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

        assertThat(multipleNames).as("Expected unique action names").isEmpty();
    }

    private static void assertActionProperties(final String connectorId, final JsonNode action, final String actionName,
        final JsonNode catalogedJsonSchema) {
        final JsonNode actionDefinition = action.get("definition");

        final JsonNode propertiesFromCatalog = catalogedJsonSchema.get("properties");

        // make sure that all action properties are as defined in
        // the connector
        StreamSupport.stream(actionDefinition.get("propertyDefinitionSteps").spliterator(), true)
            .flatMap(step -> StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(step.get("properties").fields(), Spliterator.CONCURRENT), true))
            .forEach(property -> {
                final String propertyName = property.getKey();
                final JsonNode propertyDefinition = property.getValue();

                final JsonNode catalogedPropertyDefinition = propertiesFromCatalog.get(propertyName);

                assertThat(catalogedPropertyDefinition)
                    .as("Definition of `%s` connector's action `%s` defines a property `%s` that is not defined in the Camel connector",
                        connectorId, actionName, propertyName)
                    .isNotNull();

                assertThat(propertyDefinition.get("componentProperty"))
                    .as("`componentProperty` field is missing for connector's %s %s action property %s", connectorId,
                        actionName, propertyName)
                    .isNotNull();
                assertThat(propertyDefinition.get("componentProperty").asBoolean())
                    .as("Definition of `%s` connector's action `%s` property `%s` should be marked as `componentProperty`",
                        connectorId, actionName, propertyName)
                    .isFalse();
                // remove Syndesis specifics
                final ObjectNode propertyDefinitionForComparisson = propertyNodeForComparisson(propertyDefinition);

                // remove properties that we would like to customize
                removeCustomizedProperties(propertyDefinitionForComparisson, catalogedPropertyDefinition);

                assertThat(propertyDefinitionForComparisson)
                    .as("Definition of `%s` connector's action's `%s` property `%s` differs from the one in Camel connector",
                        connectorId, actionName, propertyName)
                    .isEqualTo(catalogedPropertyDefinition);
            });
    }

    private static void assertConnectorProperties(final String connectorId, final JsonNode connectorPropertiesJson,
        final JsonNode componentPropertiesFromCatalog) {
        // make sure that all connector properties are as defined in
        // the connector
        connectorPropertiesJson.fields().forEachRemaining(property -> {
            final String propertyName = property.getKey();
            final JsonNode propertyDefinition = property.getValue();

            final JsonNode catalogedPropertyDefinition = componentPropertiesFromCatalog.get(propertyName);

            assertThat(catalogedPropertyDefinition)
                .as("Definition of `%s` connector has a property `%s` that is not defined in the Camel connector",
                    connectorId, propertyName)
                .isNotNull();

            assertThat(propertyDefinition.get("componentProperty"))
                .as("`componentProperty` field is missing for connector's %s property %s", connectorId, propertyName)
                .isNotNull();
            assertThat(propertyDefinition.get("componentProperty").asBoolean())
                .as("Definition of `%s` connector's property `%s` should be marked as `componentProperty`", connectorId,
                    propertyName)
                .isTrue();
            final ObjectNode propertyDefinitionForComparisson = propertyNodeForComparisson(propertyDefinition);

            // remove properties that we would like to customize
            removeCustomizedProperties(propertyDefinitionForComparisson, catalogedPropertyDefinition);

            assertThat(propertyDefinitionForComparisson)
                .as("Definition of `%s` connector's property `%s` differs from the one in Camel connector", connectorId,
                    propertyName)
                .isEqualTo(catalogedPropertyDefinition);
        });
    }

    private static MavenArtifactProvider createArtifactProvider() {
        final MavenArtifactProvider mavenArtifactProvider = new DefaultMavenArtifactProvider();

        mavenArtifactProvider.addMavenRepository("maven.central", "https://repo1.maven.org/maven2");
        mavenArtifactProvider.addMavenRepository("redhat.ga", "https://maven.repository.redhat.com/ga");
        mavenArtifactProvider.addMavenRepository("jboss.ea", "https://repository.jboss.org/nexus/content/groups/ea");

        return mavenArtifactProvider;
    }

    private static ObjectNode propertyNodeForComparisson(final JsonNode propertyDefinition) {
        final ObjectNode propertyDefinitionForComparisson = propertyDefinition.deepCopy();
        propertyDefinitionForComparisson.remove(Arrays.asList("tags", "componentProperty"));
        return propertyDefinitionForComparisson;
    }

    private static void removeCustomizedProperties(final JsonNode... nodes) {
        for (final JsonNode node : nodes) {
            ((ObjectNode) node).remove(Arrays.asList("displayName", "description", "defaultValue", "optionalPrefix"));
        }
    }
}