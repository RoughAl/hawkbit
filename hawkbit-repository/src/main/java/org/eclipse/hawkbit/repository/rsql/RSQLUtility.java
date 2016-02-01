/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.hawkbit.repository.rsql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.MapJoin;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.eclipse.hawkbit.repository.FieldNameProvider;
import org.eclipse.hawkbit.repository.FieldValueConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.data.jpa.domain.Specification;

import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.RSQLParserException;
import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.ComparisonOperator;
import cz.jirutka.rsql.parser.ast.LogicalNode;
import cz.jirutka.rsql.parser.ast.Node;
import cz.jirutka.rsql.parser.ast.OrNode;
import cz.jirutka.rsql.parser.ast.RSQLOperators;
import cz.jirutka.rsql.parser.ast.RSQLVisitor;

/**
 * A utility class which is able to parse RSQL strings into an spring data
 * {@link Specification} which then can be enhanced sql queries to filter
 * entities. RSQL parser library: https://github.com/jirutka/rsql-parser
 * 
 * <ul>
 * <li>Equal to : ==</li>
 * <li>Not equal to : !=</li>
 * <li>Less than : =lt= or <</li>
 * <li>Less than or equal to : =le= or <=</li>
 * <li>Greater than operator : =gt= or ></li>
 * <li>Greater than or equal to : =ge= or >=</li>
 * </ul>
 * Examples of RSQL expressions in both FIQL-like and alternative notation:
 * <ul>
 * <li>version==2.0.0</li>
 * <li>name==targetId1;description==plugAndPlay</li>
 * <li>name==targetId1 and description==plugAndPlay</li>
 * <li>name==targetId1;description==plugAndPlay</li>
 * <li>name==targetId1 and description==plugAndPlay</li>
 * <li>name==targetId1,description==plugAndPlay,updateStatus==UNKNOWN</li>
 * <li>name==targetId1 or description==plugAndPlay or updateStatus==UNKNOWN</li>
 * </ul>
 * 
 *
 *
 *
 */
public final class RSQLUtility {

    private static final Logger LOGGER = LoggerFactory.getLogger(RSQLUtility.class);

    /**
     * private constructor due utility class.
     */
    private RSQLUtility() {
    }

    /**
     * parses an RSQL valid string into an JPA {@link Specification} which then
     * can be used to filter for JPA entities with the given RSQL query.
     * 
     * @param rsql
     *            the rsql query
     * @param fieldNameProvider
     *            the enum class type which implements the
     *            {@link FieldNameProvider}
     * @param entityManager
     *            {@link EntityManager}
     * @return an specification which can be used with JPA
     * @throws RSQLParameterUnsupportedFieldException
     *             if a field in the RSQL string is used but not provided by the
     *             given {@code fieldNameProvider}
     * @throws RSQLParameterSyntaxException
     *             if the RSQL syntax is wrong
     */
    public static <A extends Enum<A> & FieldNameProvider, T> Specification<T> parse(final String rsql,
            final Class<A> fieldNameProvider) {
        return new RSQLSpecification<>(rsql, fieldNameProvider);
    }

    private static final class RSQLSpecification<A extends Enum<A> & FieldNameProvider, T> implements Specification<T> {

        private final String rsql;
        private final Class<A> enumType;

        private RSQLSpecification(final String rsql, final Class<A> enumType) {
            this.rsql = rsql;
            this.enumType = enumType;
        }

        /*
         * (non-Javadoc)
         * 
         * @see
         * org.springframework.data.jpa.domain.Specification#toPredicate(javax.
         * persistence.criteria .Root, javax.persistence.criteria.CriteriaQuery,
         * javax.persistence.criteria.CriteriaBuilder)
         */
        @Override
        public Predicate toPredicate(final Root<T> root, final CriteriaQuery<?> query, final CriteriaBuilder cb) {

            final Node rootNode;
            try {
                LOGGER.debug("parsing rsql string {}", rsql);
                final Set<ComparisonOperator> operators = RSQLOperators.defaultOperators();
                operators.add(new ComparisonOperator("=li=", false));
                rootNode = new RSQLParser(operators).parse(rsql);
            } catch (final RSQLParserException e) {
                throw new RSQLParameterSyntaxException(e);
            }

            final JpqQueryRSQLVisitor<A, T> jpqQueryRSQLVisitor = new JpqQueryRSQLVisitor<>(root, cb, enumType);
            final List<Predicate> accept = rootNode.<List<Predicate>, String> accept(jpqQueryRSQLVisitor);

            if (accept != null && !accept.isEmpty()) {
                return cb.and(accept.toArray(new Predicate[accept.size()]));
            }
            return cb.conjunction();

        }
    }

    /**
     * An implementation of the {@link RSQLVisitor} to visit the parsed tokens
     * and build jpa where clauses.
     * 
     *
     *
     * @param <A>
     *            the enum for providing the field name of the entity field to
     *            filter on.
     * @param <T>
     *            the entity type referenced by the root
     */
    private static final class JpqQueryRSQLVisitor<A extends Enum<A> & FieldNameProvider, T>
            implements RSQLVisitor<List<Predicate>, String> {
        public static final Character LIKE_WILDCARD = '*';

        private final Root<T> root;
        private final CriteriaBuilder cb;
        private final Class<A> enumType;
        private final SimpleTypeConverter simpleTypeConverter;

        private JpqQueryRSQLVisitor(final Root<T> root, final CriteriaBuilder cb, final Class<A> enumType) {
            this.root = root;
            this.cb = cb;
            this.enumType = enumType;
            this.simpleTypeConverter = new SimpleTypeConverter();
        }

        @Override
        public List<Predicate> visit(final AndNode node, final String param) {
            final List<Predicate> childs = acceptChilds(node);
            if (!childs.isEmpty()) {
                return toSingleList(cb.and(childs.toArray(new Predicate[childs.size()])));
            }
            return toSingleList(cb.conjunction());
        }

        @Override
        public List<Predicate> visit(final OrNode node, final String param) {
            final List<Predicate> childs = acceptChilds(node);
            if (!childs.isEmpty()) {
                return toSingleList(cb.or(childs.toArray(new Predicate[childs.size()])));
            }
            return toSingleList(cb.conjunction());
        }

        private List<Predicate> toSingleList(final Predicate predicate) {
            return Collections.singletonList(predicate);
        }

        private String getAndValidatePropertyFieldName(final A propertyEnum, final ComparisonNode node) {
            String finalProperty = propertyEnum.getFieldName();
            final String[] graph = node.getSelector().split("\\" + FieldNameProvider.SUB_ATTRIBUTE_SEPERATOR);

            validateMapParamter(propertyEnum, node, graph);

            // sub entity need minium 1 dot
            if (!propertyEnum.getSubEntityAttributes().isEmpty() && graph.length < 2) {
                throw createRSQLParameterUnsupportedException(node);
            }

            for (int i = 1; i < graph.length; i++) {
                final String propertyField = graph[i];
                finalProperty += FieldNameProvider.SUB_ATTRIBUTE_SEPERATOR + propertyField;

                // the key of map is not in the graph
                if (propertyEnum.isMap() && graph.length == (i + 1)) {
                    continue;
                }

                if (!propertyEnum.containsSubEntityAttribute(propertyField)) {
                    throw createRSQLParameterUnsupportedException(node);
                }
            }

            return finalProperty;
        }

        private void validateMapParamter(final A propertyEnum, final ComparisonNode node, final String[] graph) {
            if (!propertyEnum.isMap()) {
                return;

            }
            if (!propertyEnum.getSubEntityAttributes().isEmpty()) {
                throw new UnsupportedOperationException("Currently subentity attributes for maps are not supported");
            }

            // enum.key
            final int minAttributeForMap = 2;
            if (graph.length != minAttributeForMap) {
                throw new RSQLParameterUnsupportedFieldException("The syntax of the given map search parameter field {"
                        + node.getSelector() + "} is wrong. Syntax is: fieldname.keyname", new Exception());
            }
        }

        private RSQLParameterUnsupportedFieldException createRSQLParameterUnsupportedException(
                final ComparisonNode node) {
            return new RSQLParameterUnsupportedFieldException(
                    "The given search parameter field {" + node.getSelector()
                            + "} does not exist, must be one of the following fields {" + getExpectedFieldList() + "}",
                    new Exception());
        }

        private Path<Object> getFieldPath(final A enumField, final String finalProperty) {
            Path<Object> fieldPath = null;
            final String[] split = finalProperty.split("\\" + FieldNameProvider.SUB_ATTRIBUTE_SEPERATOR);
            if (split.length == 0) {
                return root.get(split[0]);
            }

            for (int i = 0; i < split.length; i++) {
                final boolean isMapKeyField = enumField.isMap() && i == (split.length - 1);
                if (isMapKeyField) {
                    return fieldPath;
                }

                final String fieldNameSplit = split[i];
                fieldPath = (fieldPath != null) ? fieldPath.get(fieldNameSplit) : root.get(fieldNameSplit);
            }
            return fieldPath;
        }

        @Override
        public List<Predicate> visit(final ComparisonNode node, final String param) {
            A fieldName = null;
            try {
                fieldName = getFieldEnumByName(node);
            } catch (final IllegalArgumentException e) {
                throw new RSQLParameterUnsupportedFieldException("The given search parameter field {"
                        + node.getSelector() + "} does not exist, must be one of the following fields {"
                        + Arrays.stream(enumType.getEnumConstants()).map(v -> v.name().toLowerCase())
                                .collect(Collectors.toList())
                        + "}", e);

            }
            final String finalProperty = getAndValidatePropertyFieldName(fieldName, node);

            final List<String> values = node.getArguments();
            final List<Object> transformedValue = new ArrayList<>();
            final Path<Object> fieldPath = getFieldPath(fieldName, finalProperty);

            for (final String value : values) {
                transformedValue.add(convertValueIfNecessary(node, fieldName, value, fieldPath));
            }

            return mapToPredicate(node, fieldPath, node.getArguments(), transformedValue, fieldName);
        }

        private List<String> getExpectedFieldList() {
            final List<String> expectedFieldList = Arrays.stream(enumType.getEnumConstants())
                    .filter(enumField -> enumField.getSubEntityAttributes().isEmpty()).map(enumField -> {
                        final String enumFieldName = enumField.name().toLowerCase();

                        if (enumField.isMap()) {
                            return enumFieldName + FieldNameProvider.SUB_ATTRIBUTE_SEPERATOR + "keyName";
                        }

                        return enumFieldName;
                    }).collect(Collectors.toList());

            final List<String> expectedSubFieldList = Arrays.stream(enumType.getEnumConstants())
                    .filter(enumField -> !enumField.getSubEntityAttributes().isEmpty()).flatMap(enumField -> {
                        final List<String> subEntity = enumField.getSubEntityAttributes().stream()
                                .map(fieldName -> enumField.name().toLowerCase()
                                        + FieldNameProvider.SUB_ATTRIBUTE_SEPERATOR + fieldName)
                                .collect(Collectors.toList());

                        return subEntity.stream();
                    }).collect(Collectors.toList());
            expectedFieldList.addAll(expectedSubFieldList);
            return expectedFieldList;
        }

        private A getFieldEnumByName(final ComparisonNode node) {
            String enumName = node.getSelector();
            final String[] graph = enumName.split("\\" + FieldNameProvider.SUB_ATTRIBUTE_SEPERATOR);
            if (graph.length != 0) {
                enumName = graph[0];
            }
            LOGGER.debug("get fieldidentifier by name {} of enum type {}", enumName, enumType);
            return Enum.valueOf(enumType, enumName.toUpperCase());
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private Object convertValueIfNecessary(final ComparisonNode node, final A fieldName, final String value,
                final Path<Object> fieldPath) {
            // in case the value of an rsql query e.g. type==application is an
            // enum we need to
            // handle it separately because JPA needs the correct java-type to
            // build an
            // expression. So String and numeric values JPA can do it by it's
            // own but not for
            // classes like enums. So we need to transform the given value
            // string into the enum
            // class.
            final Class<? extends Object> javaType = fieldPath.getJavaType();
            if (javaType != null && javaType.isEnum()) {
                return transformEnumValue(node, value, javaType);
            }
            if (fieldName instanceof FieldValueConverter) {
                final Object convertedValue = ((FieldValueConverter) fieldName).convertValue(fieldName, value);
                if (convertedValue == null) {
                    throw new RSQLParameterUnsupportedFieldException("field {" + node.getSelector()
                            + "} must be one of the following values {"
                            + Arrays.toString(((FieldValueConverter) fieldName).possibleValues(fieldName)) + "}", null);
                } else {
                    return convertedValue;
                }
            }

            try {
                return simpleTypeConverter.convertIfNecessary(value, javaType);
            } catch (final TypeMismatchException e) {
                throw new RSQLParameterSyntaxException();
            }
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private Object transformEnumValue(final ComparisonNode node, final String value,
                final Class<? extends Object> javaType) {
            final Class<? extends Enum> tmpEnumType = (Class<? extends Enum>) javaType;
            try {
                return Enum.valueOf(tmpEnumType, value.toUpperCase());
            } catch (final IllegalArgumentException e) {
                // we could not transform the given string value into the enum
                // type, so ignore
                // it and return null and do not filter
                LOGGER.info("given value {} cannot be transformed into the correct enum type {}", value.toUpperCase(),
                        javaType);
                LOGGER.debug("value cannot be transformed to an enum", e);

                throw new RSQLParameterUnsupportedFieldException("field {" + node.getSelector()
                        + "} must be one of the following values {" + Arrays.stream(tmpEnumType.getEnumConstants())
                                .map(v -> v.name().toLowerCase()).collect(Collectors.toList())
                        + "}", e);
            }
        }

        private List<Predicate> mapToPredicate(final ComparisonNode node, final Path<Object> fieldPath,
                final List<String> values, final List<Object> transformedValues, final A enumField) {
            // only 'equal' and 'notEqual' can handle transformed value like
            // enums. The JPA API
            // cannot handle object types for greaterThan etc methods.
            final Object transformedValue = transformedValues.get(0);
            final String value = values.get(0);
            final List<Predicate> singleList = new ArrayList<>();

            final Predicate mapPredicate = mapToMapPredicate(node, fieldPath, enumField);
            if (mapPredicate != null) {
                singleList.add(mapPredicate);
            }

            final Path<Object> mappedFieldPath = getMapValueFieldPath(enumField, fieldPath);

            addPredicateByOperator(node, fieldPath, transformedValues, transformedValue, value, singleList,
                    mappedFieldPath);
            return Collections.unmodifiableList(singleList);
        }

        private void addPredicateByOperator(final ComparisonNode node, final Path<Object> fieldPath,
                final List<Object> transformedValues, final Object transformedValue, final String value,
                final List<Predicate> singleList, final Path<Object> mappedFieldPath) {
            switch (node.getOperator().getSymbol()) {
            case "=li=":
                singleList.add(
                        cb.like(cb.upper(pathOfString(mappedFieldPath)), transformedValue.toString().toUpperCase()));
                break;
            case "==":
                singleList.add(getEqualToPredicate(transformedValue, mappedFieldPath));
                break;
            case "!=":
                singleList.add(cb.notEqual(mappedFieldPath, transformedValue));
                break;
            case "=gt=":
                singleList.add(cb.greaterThan(pathOfString(mappedFieldPath), value));
                break;
            case "=ge=":
                singleList.add(cb.greaterThanOrEqualTo(pathOfString(mappedFieldPath), value));
                break;
            case "=lt=":
                singleList.add(cb.lessThan(pathOfString(mappedFieldPath), value));
                break;
            case "=le=":
                singleList.add(cb.lessThanOrEqualTo(pathOfString(mappedFieldPath), value));
                break;
            case "=in=":
                singleList.add(fieldPath.in(transformedValues));
                break;
            case "=out=":
                singleList.add(cb.not(fieldPath.in(transformedValues)));
                break;
            default:
                LOGGER.info("operator symbol {} is either not supported or not implemented");
            }
        }

        private Path<Object> getMapValueFieldPath(final A enumField, final Path<Object> fieldPath) {
            if (!enumField.isMap() || enumField.getValueFieldName() == null) {
                return fieldPath;
            }
            return fieldPath.get(enumField.getValueFieldName());
        }

        private Predicate mapToMapPredicate(final ComparisonNode node, final Path<Object> fieldPath,
                final A enumField) {
            if (!enumField.isMap()) {
                return null;
            }
            final String[] graph = node.getSelector().split("\\" + FieldNameProvider.SUB_ATTRIBUTE_SEPERATOR);
            final String keyValue = graph[graph.length - 1];
            if (fieldPath instanceof MapJoin) {
                return cb.equal(((MapJoin<?, ?, ?>) fieldPath).key(), keyValue);
            }

            return cb.equal(fieldPath.get(enumField.getKeyFieldName()), keyValue);
        }

        private Predicate getEqualToPredicate(final Object transformedValue, final Path<Object> fieldPath) {
            if (transformedValue instanceof String) {
                final String preFormattedValue = ((String) transformedValue).replace(LIKE_WILDCARD, '%');
                return cb.like(cb.upper(pathOfString(fieldPath)), preFormattedValue.toUpperCase());
            }
            return cb.equal(fieldPath, transformedValue);
        }

        @SuppressWarnings("unchecked")
        private <Y> Path<Y> pathOfString(final Path<?> path) {
            return (Path<Y>) path;
        }

        private List<Predicate> acceptChilds(final LogicalNode node) {
            final List<Node> children = node.getChildren();
            final List<Predicate> childs = new ArrayList<>();
            for (final Node node2 : children) {
                final List<Predicate> accept = node2.accept(this);
                if (accept != null && !accept.isEmpty()) {
                    childs.addAll(accept);
                } else {
                    LOGGER.debug("visit logical node children but could not parse it, ignoring {}", node2);
                }
            }
            return childs;
        }

    }
}
