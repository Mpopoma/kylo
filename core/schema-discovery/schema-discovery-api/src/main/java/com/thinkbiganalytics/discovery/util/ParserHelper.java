/*
 * Copyright (c) 2016. Teradata Inc.
 */

package com.thinkbiganalytics.discovery.util;

import com.thinkbiganalytics.discovery.schema.DataTypeDescriptor;
import com.thinkbiganalytics.discovery.schema.Field;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.sql.JDBCType;
import java.sql.Types;
import java.util.List;

/**
 * Provides utility methods useful for writing parsers
 */
public class ParserHelper {

    private static final Logger log = LoggerFactory.getLogger(ParserHelper.class);

    /**
     * Maximum number of characters to sample from a file protecting from memory
     */
    private static int MAX_CHARS = 64000;

    private static int MAX_ROWS = 1000;

    /**
     * Extracts the given number of rows from the file and returns a new reader for the sample.  This method protects memory in the case where a large file can be submitted with no delimiters.
     */
    public static String extractSampleLines(InputStream is, Charset charset, int rows) throws IOException {

        StringWriter sw = new StringWriter();
        Validate.notNull(is, "empty input stream");
        Validate.notNull(charset, "charset cannot be null");
        Validate.exclusiveBetween(1, MAX_ROWS, rows, "invalid number of sample rows");

        // Sample the file in case there are no newlines we don't want to
        StringWriter swBlock = new StringWriter();
        IOUtils.copyLarge(new InputStreamReader(is, charset), swBlock, -1, MAX_CHARS);
        try (BufferedReader br = new BufferedReader(new StringReader(swBlock.toString()))) {
            IOUtils.closeQuietly(swBlock);
            String line = br.readLine();
            int linesRead = 0;
            for (int i = 1; i <= rows && line != null; i++) {
                sw.write(line);
                sw.write("\n");
                linesRead++;
                line = br.readLine();
            }
            if (linesRead <= 1 && sw.toString().length() >= MAX_CHARS) {
                throw new IOException("Failed to detect newlines for sample file.");
            }
        }

        return sw.toString();
    }


    /*
    Convert the JDBC sql type to a hive type
     */
    public static String sqlTypeToHiveType(JDBCType jdbcType) {
        if (jdbcType != null) {
            Integer type = jdbcType.getVendorTypeNumber();
            switch (type) {
                case Types.BIGINT:
                    return "bigint";
                case Types.NUMERIC:
                case Types.DOUBLE:
                case Types.DECIMAL:
                    return "double";
                case Types.INTEGER:
                    return "int";
                case Types.FLOAT:
                    return "float";
                case Types.TINYINT:
                    return "tinyint";
                case Types.DATE:
                    return "date";
                case Types.TIMESTAMP:
                    return "timestamp";
                case Types.BOOLEAN:
                    return "boolean";
                case Types.BINARY:
                    return "binary";
                default:
                    return "string";
            }
        }
        return null;
    }

    /**
     * Derive the corresponding data type from sample values
     *
     * @param values a list of string values
     * @return the JDBC data type
     */
    public static JDBCType deriveJDBCDataType(List<String> values) {

        JDBCType guess = null;
        if (values != null) {
            for (String v : values) {
                JDBCType currentPass;
                try {
                    Integer.parseInt(v);
                    currentPass = JDBCType.INTEGER;
                } catch (NumberFormatException e) {
                    try {
                        Double.parseDouble(v);
                        currentPass = JDBCType.DOUBLE;
                    } catch (NumberFormatException ex) {
                        // return immediately if we know its non-numeric
                        return JDBCType.VARCHAR;
                    }
                }
                // If we ever see a double then use that
                if (guess == null || currentPass == JDBCType.DOUBLE) {
                    guess = currentPass;
                }
            }
        }
        return (guess == null ? JDBCType.VARCHAR : guess);
    }

    /**
     * Derive data types
     *
     * @param type   the target database platform
     * @param fields the fields
     */
    public static void deriveDataTypes(TableSchemaType type, List<? extends Field> fields) {
        for (Field field : fields) {
            if (StringUtils.isEmpty(field.getDerivedDataType())) {
                JDBCType jdbcType = JDBCType.VARCHAR;
                ;
                try {
                    if (!StringUtils.isEmpty(field.getNativeDataType())) {
                        jdbcType = JDBCType.valueOf(field.getNativeDataType());
                    } else {
                        jdbcType = deriveJDBCDataType(field.getSampleValues());
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Unable to convert data type [?] will be converted to VARCHAR", field.getNativeDataType());
                }

                switch (type) {
                    case HIVE:
                        String hiveType = sqlTypeToHiveType(jdbcType);
                        field.setDerivedDataType(hiveType);
                        field.setDataTypeDescriptor(hiveTypeToDescriptor(hiveType));
                        break;
                    case RDBMS:
                        field.setDerivedDataType(jdbcType.getName());
                }
            }
        }
    }

    /*
    Returns whether the provided field represents a complex structure such as ARRAY, STRUCT, or BINARY
 */
    public static DataTypeDescriptor hiveTypeToDescriptor(String hiveType) {
        HiveDataTypeDescriptor descriptor = new HiveDataTypeDescriptor();
        if (hiveType != null) {
            hiveType = hiveType.toLowerCase();
            switch (hiveType) {
                case "boolean":
                case "string":
                    break;
                case "bigint":
                case "double":
                case "int":
                case "float":
                case "tinyint":
                    descriptor.setNumeric(true);
                    break;
                case "date":
                case "timestamp":
                    descriptor.setDate(true);
                    break;
                default:
                    descriptor.setComplex(true);
            }
        }
        return descriptor;
    }

    static class HiveDataTypeDescriptor implements DataTypeDescriptor {

        boolean isDate;

        boolean isNumeric;

        boolean isComplex;

        @Override
        public Boolean isDate() {
            return isDate;
        }

        @Override
        public Boolean isNumeric() {
            return isNumeric;
        }

        @Override
        public Boolean isComplex() {
            return isComplex;
        }

        public void setDate(boolean date) {
            isDate = date;
        }

        public void setNumeric(boolean numeric) {
            isNumeric = numeric;
        }

        public void setComplex(boolean complex) {
            isComplex = complex;
        }

    }
}