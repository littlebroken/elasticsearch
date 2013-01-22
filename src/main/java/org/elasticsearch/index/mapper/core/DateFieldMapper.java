/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.index.mapper.core;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.NumericRangeFilter;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Numbers;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.joda.DateMathParser;
import org.elasticsearch.common.joda.FormatDateTimeFormatter;
import org.elasticsearch.common.joda.Joda;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.analysis.NumericDateAnalyzer;
import org.elasticsearch.index.codec.postingsformat.PostingsFormatProvider;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.fielddata.IndexFieldDataService;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.index.search.NumericRangeFieldDataFilter;
import org.elasticsearch.index.similarity.SimilarityProvider;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.index.mapper.MapperBuilders.dateField;
import static org.elasticsearch.index.mapper.core.TypeParsers.parseDateTimeFormatter;
import static org.elasticsearch.index.mapper.core.TypeParsers.parseNumberField;

/**
 *
 */
public class DateFieldMapper extends NumberFieldMapper<Long> {

    public static final String CONTENT_TYPE = "date";

    public static class Defaults extends NumberFieldMapper.Defaults {
        public static final FormatDateTimeFormatter DATE_TIME_FORMATTER = Joda.forPattern("dateOptionalTime");

        public static final FieldType FIELD_TYPE = new FieldType(NumberFieldMapper.Defaults.FIELD_TYPE);

        static {
            FIELD_TYPE.freeze();
        }

        public static final String NULL_VALUE = null;

        public static final TimeUnit TIME_UNIT = TimeUnit.MILLISECONDS;
        public static final boolean PARSE_UPPER_INCLUSIVE = true;
    }

    public static class Builder extends NumberFieldMapper.Builder<Builder, DateFieldMapper> {

        protected TimeUnit timeUnit = Defaults.TIME_UNIT;

        protected String nullValue = Defaults.NULL_VALUE;

        protected FormatDateTimeFormatter dateTimeFormatter = Defaults.DATE_TIME_FORMATTER;

        public Builder(String name) {
            super(name, new FieldType(Defaults.FIELD_TYPE));
            builder = this;
        }

        public Builder timeUnit(TimeUnit timeUnit) {
            this.timeUnit = timeUnit;
            return this;
        }

        public Builder nullValue(String nullValue) {
            this.nullValue = nullValue;
            return this;
        }

        public Builder dateTimeFormatter(FormatDateTimeFormatter dateTimeFormatter) {
            this.dateTimeFormatter = dateTimeFormatter;
            return this;
        }

        @Override
        public DateFieldMapper build(BuilderContext context) {
            boolean parseUpperInclusive = Defaults.PARSE_UPPER_INCLUSIVE;
            if (context.indexSettings() != null) {
                parseUpperInclusive = context.indexSettings().getAsBoolean("index.mapping.date.parse_upper_inclusive", Defaults.PARSE_UPPER_INCLUSIVE);
            }
            fieldType.setOmitNorms(fieldType.omitNorms() && boost == 1.0f);
            DateFieldMapper fieldMapper = new DateFieldMapper(buildNames(context), dateTimeFormatter,
                    precisionStep, fuzzyFactor, boost, fieldType, nullValue,
                    timeUnit, parseUpperInclusive, ignoreMalformed(context), provider, similarity);
            fieldMapper.includeInAll(includeInAll);
            return fieldMapper;
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            DateFieldMapper.Builder builder = dateField(name);
            parseNumberField(builder, name, node, parserContext);
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String propName = Strings.toUnderscoreCase(entry.getKey());
                Object propNode = entry.getValue();
                if (propName.equals("null_value")) {
                    builder.nullValue(propNode.toString());
                } else if (propName.equals("format")) {
                    builder.dateTimeFormatter(parseDateTimeFormatter(propName, propNode));
                } else if (propName.equals("numeric_resolution")) {
                    builder.timeUnit(TimeUnit.valueOf(propNode.toString().toUpperCase()));
                }
            }
            return builder;
        }
    }

    protected final FormatDateTimeFormatter dateTimeFormatter;

    private final boolean parseUpperInclusive;

    private final DateMathParser dateMathParser;

    private String nullValue;

    protected final TimeUnit timeUnit;

    protected DateFieldMapper(Names names, FormatDateTimeFormatter dateTimeFormatter, int precisionStep, String fuzzyFactor,
                              float boost, FieldType fieldType,
                              String nullValue, TimeUnit timeUnit, boolean parseUpperInclusive, Explicit<Boolean> ignoreMalformed,
                              PostingsFormatProvider provider, SimilarityProvider similarity) {
        super(names, precisionStep, fuzzyFactor, boost, fieldType,
                ignoreMalformed, new NamedAnalyzer("_date/" + precisionStep,
                new NumericDateAnalyzer(precisionStep, dateTimeFormatter.parser())),
                new NamedAnalyzer("_date/max", new NumericDateAnalyzer(Integer.MAX_VALUE, dateTimeFormatter.parser())),
                provider, similarity);
        this.dateTimeFormatter = dateTimeFormatter;
        this.nullValue = nullValue;
        this.timeUnit = timeUnit;
        this.parseUpperInclusive = parseUpperInclusive;
        this.dateMathParser = new DateMathParser(dateTimeFormatter, timeUnit);
    }

    @Override
    public FieldType defaultFieldType() {
        return Defaults.FIELD_TYPE;
    }

    @Override
    public org.elasticsearch.index.fielddata.FieldDataType fieldDataType2() {
        // long for now, need to think about scripts and getting a DateTime back?
        return new org.elasticsearch.index.fielddata.FieldDataType("long");
    }

    @Override
    protected double parseFuzzyFactor(String fuzzyFactor) {
        if (fuzzyFactor == null) {
            return 1.0d;
        }
        try {
            return TimeValue.parseTimeValue(fuzzyFactor, null).millis();
        } catch (Exception e) {
            return Double.parseDouble(fuzzyFactor);
        }
    }

    @Override
    protected int maxPrecisionStep() {
        return 64;
    }

    @Override
    public Long value(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof BytesRef) {
            return Numbers.bytesToLong((BytesRef) value);
        }
        return parseStringValue(value.toString());
    }

    /**
     * Dates should return as a string.
     */
    @Override
    public Object valueForSearch(Object value) {
        if (value instanceof String) {
            // assume its the string that was indexed, just return it... (for example, with get)
            return value;
        }
        Long val = value(value);
        if (val == null) {
            return null;
        }
        return dateTimeFormatter.printer().print(val);
    }

    @Override
    public BytesRef indexedValueForSearch(Object value) {
        BytesRef bytesRef = new BytesRef();
        NumericUtils.longToPrefixCoded(parseValue(value), precisionStep(), bytesRef);
        return bytesRef;
    }

    private long parseValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof BytesRef) {
            return dateTimeFormatter.parser().parseMillis(((BytesRef) value).utf8ToString());
        }
        return dateTimeFormatter.parser().parseMillis(value.toString());
    }

    private String convertToString(Object value) {
        if (value instanceof BytesRef) {
            return ((BytesRef) value).utf8ToString();
        }
        return value.toString();
    }

    @Override
    public Query fuzzyQuery(String value, String minSim, int prefixLength, int maxExpansions, boolean transpositions) {
        long iValue = dateMathParser.parse(value, System.currentTimeMillis());
        long iSim;
        try {
            iSim = TimeValue.parseTimeValue(minSim, null).millis();
        } catch (Exception e) {
            // not a time format
            iSim = (long) Double.parseDouble(minSim);
        }
        return NumericRangeQuery.newLongRange(names.indexName(), precisionStep,
                iValue - iSim,
                iValue + iSim,
                true, true);
    }

    @Override
    public Query fuzzyQuery(String value, double minSim, int prefixLength, int maxExpansions, boolean transpositions) {
        long iValue = dateMathParser.parse(value, System.currentTimeMillis());
        long iSim = (long) (minSim * dFuzzyFactor);
        return NumericRangeQuery.newLongRange(names.indexName(), precisionStep,
                iValue - iSim,
                iValue + iSim,
                true, true);
    }

    @Override
    public Query termQuery(Object value, @Nullable QueryParseContext context) {
        long now = context == null ? System.currentTimeMillis() : context.nowInMillis();
        long lValue = dateMathParser.parse(convertToString(value), now);
        return NumericRangeQuery.newLongRange(names.indexName(), precisionStep,
                lValue, lValue, true, true);
    }

    @Override
    public Filter termFilter(Object value, @Nullable QueryParseContext context) {
        long now = context == null ? System.currentTimeMillis() : context.nowInMillis();
        long lValue = dateMathParser.parse(convertToString(value), now);
        return NumericRangeFilter.newLongRange(names.indexName(), precisionStep,
                lValue, lValue, true, true);
    }

    @Override
    public Query rangeQuery(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context) {
        long now = context == null ? System.currentTimeMillis() : context.nowInMillis();
        return NumericRangeQuery.newLongRange(names.indexName(), precisionStep,
                lowerTerm == null ? null : dateMathParser.parse(convertToString(lowerTerm), now),
                upperTerm == null ? null : (includeUpper && parseUpperInclusive) ? dateMathParser.parseUpperInclusive(convertToString(upperTerm), now) : dateMathParser.parse(convertToString(upperTerm), now),
                includeLower, includeUpper);
    }

    @Override
    public Filter rangeFilter(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context) {
        long now = context == null ? System.currentTimeMillis() : context.nowInMillis();
        return NumericRangeFilter.newLongRange(names.indexName(), precisionStep,
                lowerTerm == null ? null : dateMathParser.parse(convertToString(lowerTerm), now),
                upperTerm == null ? null : (includeUpper && parseUpperInclusive) ? dateMathParser.parseUpperInclusive(convertToString(upperTerm), now) : dateMathParser.parse(convertToString(upperTerm), now),
                includeLower, includeUpper);
    }

    @Override
    public Filter rangeFilter(IndexFieldDataService fieldData, Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context) {
        long now = context == null ? System.currentTimeMillis() : context.nowInMillis();
        return NumericRangeFieldDataFilter.newLongRange((IndexNumericFieldData) fieldData.getForField(this),
                lowerTerm == null ? null : dateMathParser.parse(convertToString(lowerTerm), now),
                upperTerm == null ? null : (includeUpper && parseUpperInclusive) ? dateMathParser.parseUpperInclusive(convertToString(upperTerm), now) : dateMathParser.parse(convertToString(upperTerm), now),
                includeLower, includeUpper);
    }

    @Override
    public Filter nullValueFilter() {
        if (nullValue == null) {
            return null;
        }
        long value = parseStringValue(nullValue);
        return NumericRangeFilter.newLongRange(names.indexName(), precisionStep,
                value,
                value,
                true, true);
    }


    @Override
    protected boolean customBoost() {
        return true;
    }

    @Override
    protected Field innerParseCreateField(ParseContext context) throws IOException {
        String dateAsString = null;
        Long value = null;
        float boost = this.boost;
        if (context.externalValueSet()) {
            Object externalValue = context.externalValue();
            if (externalValue instanceof Number) {
                value = ((Number) externalValue).longValue();
            } else {
                dateAsString = (String) externalValue;
                if (dateAsString == null) {
                    dateAsString = nullValue;
                }
            }
        } else {
            XContentParser parser = context.parser();
            XContentParser.Token token = parser.currentToken();
            if (token == XContentParser.Token.VALUE_NULL) {
                dateAsString = nullValue;
            } else if (token == XContentParser.Token.VALUE_NUMBER) {
                value = parser.longValue();
            } else if (token == XContentParser.Token.START_OBJECT) {
                String currentFieldName = null;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentFieldName = parser.currentName();
                    } else {
                        if ("value".equals(currentFieldName) || "_value".equals(currentFieldName)) {
                            if (token == XContentParser.Token.VALUE_NULL) {
                                dateAsString = nullValue;
                            } else if (token == XContentParser.Token.VALUE_NUMBER) {
                                value = parser.longValue();
                            } else {
                                dateAsString = parser.text();
                            }
                        } else if ("boost".equals(currentFieldName) || "_boost".equals(currentFieldName)) {
                            boost = parser.floatValue();
                        } else {
                            throw new ElasticSearchIllegalArgumentException("unknown property [" + currentFieldName + "]");
                        }
                    }
                }
            } else {
                dateAsString = parser.text();
            }
        }

        if (value != null) {
            LongFieldMapper.CustomLongNumericField field = new LongFieldMapper.CustomLongNumericField(this, timeUnit.toMillis(value), fieldType);
            field.setBoost(boost);
            return field;
        }

        if (dateAsString == null) {
            return null;
        }
        if (context.includeInAll(includeInAll, this)) {
            context.allEntries().addText(names.fullName(), dateAsString, boost);
        }

        value = parseStringValue(dateAsString);
        LongFieldMapper.CustomLongNumericField field = new LongFieldMapper.CustomLongNumericField(this, value, fieldType);
        field.setBoost(boost);
        return field;
    }

    @Override
    public FieldDataType fieldDataType() {
        return FieldDataType.DefaultTypes.LONG;
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public void merge(Mapper mergeWith, MergeContext mergeContext) throws MergeMappingException {
        super.merge(mergeWith, mergeContext);
        if (!this.getClass().equals(mergeWith.getClass())) {
            return;
        }
        if (!mergeContext.mergeFlags().simulate()) {
            this.nullValue = ((DateFieldMapper) mergeWith).nullValue;
        }
    }

    @Override
    protected void doXContentBody(XContentBuilder builder) throws IOException {
        super.doXContentBody(builder);
        if (precisionStep != Defaults.PRECISION_STEP) {
            builder.field("precision_step", precisionStep);
        }
        if (fuzzyFactor != Defaults.FUZZY_FACTOR) {
            builder.field("fuzzy_factor", fuzzyFactor);
        }
        builder.field("format", dateTimeFormatter.format());
        if (nullValue != null) {
            builder.field("null_value", nullValue);
        }
        if (includeInAll != null) {
            builder.field("include_in_all", includeInAll);
        }
        if (timeUnit != Defaults.TIME_UNIT) {
            builder.field("numeric_resolution", timeUnit.name().toLowerCase());
        }
    }

    private long parseStringValue(String value) {
        try {
            return dateTimeFormatter.parser().parseMillis(value);
        } catch (RuntimeException e) {
            try {
                long time = Long.parseLong(value);
                return timeUnit.toMillis(time);
            } catch (NumberFormatException e1) {
                throw new MapperParsingException("failed to parse date field [" + value + "], tried both date format [" + dateTimeFormatter.format() + "], and timestamp number", e);
            }
        }
    }
}