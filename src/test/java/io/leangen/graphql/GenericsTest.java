package io.leangen.graphql;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.lang.reflect.AnnotatedType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.Scalars;
import graphql.relay.Relay;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import io.leangen.geantyref.TypeToken;
import io.leangen.graphql.annotations.GraphQLId;
import io.leangen.graphql.annotations.GraphQLNonNull;
import io.leangen.graphql.metadata.strategy.value.ValueMapperFactory;
import io.leangen.graphql.metadata.strategy.value.gson.GsonValueMapperFactory;
import io.leangen.graphql.metadata.strategy.value.jackson.JacksonValueMapperFactory;
import io.leangen.graphql.services.GenericItemRepo;

import static io.leangen.graphql.support.GraphQLTypeAssertions.assertArgumentsPresent;
import static io.leangen.graphql.support.GraphQLTypeAssertions.assertListOf;
import static io.leangen.graphql.support.GraphQLTypeAssertions.assertListOfNonNull;
import static io.leangen.graphql.support.GraphQLTypeAssertions.assertListOfRelayIds;
import static io.leangen.graphql.support.GraphQLTypeAssertions.assertNonNull;
import static io.leangen.graphql.util.GraphQLUtils.isRelayId;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("unchecked")
@RunWith(Parameterized.class)
public class GenericsTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Object[] data() {
        return new Object[] { new JacksonValueMapperFactory(), new GsonValueMapperFactory() };
    }
    
    // IMPORTANT! All type declarations have to stay outside of tests (can not be inlined)
    // as the annotation parser treats them differently and discards the annotations otherwise.
    // This is a JDK8 bug: http://stackoverflow.com/questions/39952812
    private static final AnnotatedType nonNullString = new TypeToken<GenericItemRepo<@GraphQLNonNull String>>() {
    }.getAnnotatedType();
    private static final AnnotatedType dateId = new TypeToken<GenericItemRepo<@GraphQLId(relayId = true) Date>>() {
    }.getAnnotatedType();
    private static final AnnotatedType listOfWildcardNumbers = new TypeToken<GenericItemRepo<@GraphQLNonNull List<? extends Number>>>() {
    }.getAnnotatedType();
    private static final AnnotatedType arrayOfListsOfNumbers = new TypeToken<GenericItemRepo<@GraphQLNonNull List<Number> @GraphQLNonNull []>>() {
    }.getAnnotatedType();

    @Parameterized.Parameter
    public ValueMapperFactory valueMapperFactory;
    
    private static final String ERRORS = "Error(s) during query resolution";

    @Test
    public void testNonNullGenerics() {
        GenericItemRepo<@GraphQLNonNull String> nonNullStringService = new GenericItemRepo<>();
        nonNullStringService.addItem("pooch", "Strudel, the poodle");
        nonNullStringService.addItem("booze", "Fire-water");

        GraphQLSchema schemaWithNonNullGenerics = new PreconfiguredSchemaGenerator()
                .withOperationsFromSingleton(nonNullStringService, nonNullString)
                .withValueMapperFactory(valueMapperFactory)
                .withDefaults()
                .generate();

        GraphQLOutputType itemType = schemaWithNonNullGenerics.getQueryType().getFieldDefinition("getItem").getType();
        assertNonNull(itemType, Scalars.GraphQLString);

        GraphQLOutputType itemCollectionType = schemaWithNonNullGenerics.getQueryType().getFieldDefinition("getAllItems").getType();
        assertListOfNonNull(itemCollectionType, Scalars.GraphQLString);

        GraphQLFieldDefinition addOneItem = schemaWithNonNullGenerics.getMutationType().getFieldDefinition("addItem");
        assertEquals(addOneItem.getArguments().size(), 2);
        assertArgumentsPresent(addOneItem, "item", "name");
        assertNonNull(addOneItem.getArgument("item").getType(), Scalars.GraphQLString);

        GraphQLFieldDefinition addManyItems = schemaWithNonNullGenerics.getMutationType().getFieldDefinition("addItems");
        assertEquals(addManyItems.getArguments().size(), 1);
        assertArgumentsPresent(addManyItems, "items");
        assertListOfNonNull(addManyItems.getArgument("items").getType(), Scalars.GraphQLString);

        GraphQL graphQL = GraphQL.newGraphQL(schemaWithNonNullGenerics).build();
        ExecutionResult result = graphQL.execute("{ getAllItems }");
        assertTrue(ERRORS, result.getErrors().isEmpty());
        assertEquals(new ArrayList<>(nonNullStringService.getAllItems()), ((Map<String, Object>) result.getData()).get("getAllItems"));
    }

    @Test
    public void testRelayIdGenerics() {
        GenericItemRepo<@GraphQLId(relayId = true) Date> dateIdService = new GenericItemRepo<>();
        final Date firstEvent = new Date(1000);
        final Date secondEvent = new Date(2000);
        dateIdService.addItem("firstEvent", firstEvent);
        dateIdService.addItem("secondEvent", secondEvent);

        GraphQLSchema schemaWithDateIds = new PreconfiguredSchemaGenerator()
                .withOperationsFromSingleton(dateIdService, dateId)
                .withValueMapperFactory(valueMapperFactory)
                .withDefaults()
                .generate();

        GraphQLOutputType itemType = schemaWithDateIds.getQueryType().getFieldDefinition("getItem").getType();
        assertTrue(isRelayId(itemType));

        GraphQLOutputType itemCollectionType = schemaWithDateIds.getQueryType().getFieldDefinition("getAllItems").getType();
        assertListOfRelayIds(itemCollectionType);

        GraphQLFieldDefinition addOneItem = schemaWithDateIds.getMutationType().getFieldDefinition("addItem");
        assertTrue(isRelayId(addOneItem.getArgument("id")));

        GraphQLFieldDefinition addManyItems = schemaWithDateIds.getMutationType().getFieldDefinition("addItems");
        assertListOfRelayIds(addManyItems.getArgument("items").getType());
        
        GraphQL graphQL = GraphQL.newGraphQL(schemaWithDateIds).build();
        String jsonDate = valueMapperFactory.getValueMapper().toString(firstEvent);
        String relayId = new Relay().toGlobalId("QUERY_ROOT", jsonDate);
        ExecutionResult result = graphQL.execute("{ contains(id: \"" + relayId+ "\") }");
        assertTrue(ERRORS, result.getErrors().isEmpty());
        assertEquals(relayId, ((Map<String, Object>) result.getData()).get("contains"));
        //Search again but using raw (non Relay encoded) ID this time. For now, this is supported.
        result = graphQL.execute("{ contains(id: \"" + jsonDate.replace("\"", "\\\"") + "\") }");
        assertTrue(ERRORS, result.getErrors().isEmpty());
        assertEquals(relayId, ((Map<String, Object>) result.getData()).get("contains"));
    }

    @Test
    public void testWildcardGenerics() {
        GenericItemRepo<@GraphQLNonNull List<? extends Number>> wildcardNumberService = new GenericItemRepo<>();
        wildcardNumberService.addItem("player1", Arrays.asList(12, 13.4, new BigDecimal("4000")));
        wildcardNumberService.addItem("player2", Arrays.asList(new BigDecimal("12.56"), 14.78));

        GraphQLSchema schemaWithGenerics = new PreconfiguredSchemaGenerator()
                .withOperationsFromSingleton(wildcardNumberService, listOfWildcardNumbers)
                .withValueMapperFactory(valueMapperFactory)
                .withDefaults()
                .generate();

        GraphQLOutputType itemType = schemaWithGenerics.getQueryType().getFieldDefinition("getItem").getType();
        assertNonNull(itemType, GraphQLList.class);
        assertListOf(((graphql.schema.GraphQLNonNull) itemType).getWrappedType(), Scalars.GraphQLBigDecimal);

        GraphQLOutputType itemCollectionType = schemaWithGenerics.getQueryType().getFieldDefinition("getAllItems").getType();
        assertListOfNonNull(itemCollectionType, GraphQLList.class);
        assertListOf(((graphql.schema.GraphQLNonNull) ((GraphQLList) itemCollectionType).getWrappedType()).getWrappedType(), Scalars.GraphQLBigDecimal);

        GraphQLFieldDefinition addOneItem = schemaWithGenerics.getMutationType().getFieldDefinition("addItem");
        GraphQLType itemArgType = addOneItem.getArgument("item").getType();
        assertNonNull(itemArgType, GraphQLList.class);
        assertListOf(((graphql.schema.GraphQLNonNull) itemArgType).getWrappedType(), Scalars.GraphQLBigDecimal);

        GraphQL graphQL = GraphQL.newGraphQL(schemaWithGenerics).build();
        ExecutionResult result = graphQL.execute("{ getAllItems }");
        assertTrue(ERRORS, result.getErrors().isEmpty());
        Object[] expected = wildcardNumberService.getAllItems().toArray();
        Object[] actual = wildcardNumberService.getAllItems().toArray();
        assertArrayEquals(expected, actual);
    }

    @Test
    public void testArrayGenerics() {
        GenericItemRepo<@GraphQLNonNull List<Number> @GraphQLNonNull []> arrayNumberService = new GenericItemRepo<>();
        List<Number>[] array1 = (List<Number>[]) new List[1];
        array1[0] = Arrays.asList(12, 13.4, new BigDecimal("4000"));
        List<Number>[] array2 = (List<Number>[]) new List[1];
        array2[0] = Arrays.asList(new BigDecimal("12.56"), 14.78);
        arrayNumberService.addItem("scores1", array1);
        arrayNumberService.addItem("scores2", array2);

        GraphQLSchema schemaWithGenerics = new PreconfiguredSchemaGenerator()
                .withOperationsFromSingleton(arrayNumberService, arrayOfListsOfNumbers)
                .withValueMapperFactory(valueMapperFactory)
                .withDefaults()
                .generate();

        GraphQLOutputType itemType = schemaWithGenerics.getQueryType().getFieldDefinition("getItem").getType();
        assertNonNull(itemType, GraphQLList.class);
        GraphQLType inner = ((graphql.schema.GraphQLNonNull) itemType).getWrappedType();
        assertListOf(inner, graphql.schema.GraphQLNonNull.class);
        inner = ((graphql.schema.GraphQLNonNull) ((GraphQLList) inner).getWrappedType()).getWrappedType();
        assertListOf(inner, Scalars.GraphQLBigDecimal);

        GraphQLFieldDefinition addOneItem = schemaWithGenerics.getMutationType().getFieldDefinition("addItem");
        GraphQLType itemArgType = addOneItem.getArgument("item").getType();
        assertNonNull(itemArgType, GraphQLList.class);
        inner = ((graphql.schema.GraphQLNonNull) itemType).getWrappedType();
        assertListOf(inner, graphql.schema.GraphQLNonNull.class);
        inner = ((graphql.schema.GraphQLNonNull) ((GraphQLList) inner).getWrappedType()).getWrappedType();
        assertListOf(inner, Scalars.GraphQLBigDecimal);

        GraphQL graphQL = GraphQL.newGraphQL(schemaWithGenerics).build();
        ExecutionResult result = graphQL.execute("{ getAllItems }");
        assertTrue(ERRORS, result.getErrors().isEmpty());
        Object[] expected = arrayNumberService.getAllItems().toArray();
        Object[] actual = arrayNumberService.getAllItems().toArray();
        assertArrayEquals(expected, actual);
    }
}
