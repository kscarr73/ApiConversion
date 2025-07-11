package com.progbits.api.conversion;

import com.progbits.api.exception.ApiClassNotFoundException;
import com.progbits.api.exception.ApiException;
import com.progbits.api.model.ApiObject;
import com.progbits.api.model.ApiObjectUtils;
import com.progbits.api.parser.JsonObjectParser;
import com.progbits.api.utils.service.ApiInstance;
import com.progbits.api.utils.service.ApiService;
import com.progbits.api.writer.JsonObjectWriter;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convert from one ApiObject to Another using a Config Object
 *
 * @author Kevin.Carr
 */
public class ApiObjectConverter implements ApiService {

    private static final Logger log = LoggerFactory.getLogger(ApiObjectConverter.class);

    private static final ApiInstance<ApiObjectConverter> instance = new ApiInstance();

    public static ApiObjectConverter getInstance() {
        return instance.getInstance(ApiObjectConverter.class);
    }

    @Override
    public void configure() {
        methods.put("json2String", this::json2String);
        methods.put("string2Json", this::string2Json);

        methods.put("boolToInt", this::convertFieldFromBoolean);
        methods.put("intToBool", this::convertFieldToBoolean);

        methods.put("stringToDate", this::convertStringToDate);
        methods.put("dateToString", this::convertDateToString);

        reverseMethods.setString("json2String", "string2Json");
        reverseMethods.setString("string2Json", "json2String");
        reverseMethods.setString("boolToInt", "intToBool");
        reverseMethods.setString("intToBool", "boolToInt");
        reverseMethods.setString("stringToDate", "dateToString");
        reverseMethods.setString("dateToString", "stringToDate");
    }

    public static final String FIELD_FIELD = "field";
    public static final String FIELD_METHOD = "method";
    public static final String FIELD_DOT_FIELD = ".field";
    public static final String FIELD_DOT_METHOD = ".method";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_REQUIRED = "required";

    private static final String CONTROL_REQUIRED = "$required";
    private static final String FIELD_ROOT = "root";

    private final Map<String, ApiMethodHandler> methods = new HashMap<>();

    private static final JsonObjectParser jsonParser = new JsonObjectParser(true);
    private static final JsonObjectWriter jsonWriter = new JsonObjectWriter(true);

    /*
      Reverse Methods are used when reversing a Conversion Config object
     */
    private final ApiObject reverseMethods = new ApiObject();

    public ApiObject getReverseMethods() {
        return reverseMethods;
    }

    public Map<String, ApiMethodHandler> getMethods() {
        return methods;
    }

    public ApiObject reverseConvertObject(ApiObject convertObj) {
        ApiObject respObj = new ApiObject();

        for (var field : convertObj.keySet()) {
            if (CONTROL_REQUIRED.equals(field)) {
                continue;
            }

            if (convertObj.getType(field) == ApiObject.TYPE_STRING) {
                respObj.put(convertObj.getString(field), field);
            } else if (convertObj.getType(field) == ApiObject.TYPE_OBJECT) {
                ApiObject objField = new ApiObject();

                objField.putAll(convertObj.getObject(field));

                objField.setString(FIELD_FIELD, field);
                objField.setString(FIELD_METHOD, reverseMethods.getString(convertObj.getString(field + FIELD_DOT_METHOD)));

                respObj.setObject(convertObj.getString(field + FIELD_DOT_FIELD), objField);
            }
        }

        return respObj;
    }

    /**
     * Convert ApiObject with root list or Single using a Conversion Object
     *
     * <p>
     * Defaults includeAll to false</p>
     *
     * @param subject The subject to perform the conversion on
     * @param convertObj Field Mapping used for Conversion
     *
     * @return The Converted Object
     *
     * @throws ApiException
     */
    public ApiObject convertObject(ApiObject subject, ApiObject convertObj) throws ApiException {
        return convertObject(subject, convertObj, false);
    }

    /**
     * Convert ApiObject with root list or Single using a Conversion Object
     *
     * @param subject The subject to perform the conversion on
     * @param convertObj Field Mapping used for Conversion
     * @param includeAll true/false Return ALL fields from subject
     *
     * @return The Converted Object
     *
     * @throws ApiException
     */
    public ApiObject convertObject(ApiObject subject, ApiObject convertObj, boolean includeAll) throws ApiException {
        if (subject != null) {
            if (convertObj != null) {
                if (subject.containsKey(FIELD_ROOT)) {
                    ApiObject respObj = new ApiObject();

                    respObj.createList(FIELD_ROOT);

                    for (var entry : subject.getList(FIELD_ROOT)) {
                        respObj.getList(FIELD_ROOT).add(convertSingle(entry, convertObj, includeAll));
                    }

                    return respObj;
                } else {
                    return convertSingle(subject, convertObj, includeAll);
                }
            } else {
                log.warn("Convert Object was NULL");
                
                return ApiObjectUtils.cloneApiObject(subject, null);
            }
        } else {
            return null;
        }
    }

    /**
     * Convert a single ApiObject using a Conversion Object
     *
     * @param subject The subject to perform the conversion on
     * @param convertObj Field Mapping used for Conversion
     * @param includeAll true/false Return ALL fields from subject
     *
     * @return The Converted Object
     *
     * @throws ApiException
     */
    private ApiObject convertSingle(ApiObject subject, ApiObject convertObj, boolean includeAll) throws ApiException {
        ApiObject respObj = new ApiObject();

        if (convertObj.getType(CONTROL_REQUIRED) == ApiObject.TYPE_STRINGARRAY) {
            StringBuilder sbErr = new StringBuilder();

            for (String entry : convertObj.getStringArray(CONTROL_REQUIRED)) {
                if (!subject.isSet(entry)) {
                    if (!sbErr.isEmpty()) {
                        sbErr.append("\n");
                    }

                    sbErr.append(entry).append(" IS REQUIRED");
                }
            }

            if (!sbErr.isEmpty()) {
                throw new ApiException(400, sbErr.toString());
            }
        }

        for (String subjectFld : subject.keySet()) {
            if (convertObj.getType(subjectFld) == ApiObject.TYPE_STRING) {
                setField(respObj, convertObj.getString(subjectFld), subject.get(subjectFld));
            } else if (convertObj.getType(subjectFld) == ApiObject.TYPE_OBJECT) {
                var toMethod = convertObj.getString(subjectFld + FIELD_DOT_METHOD);

                if (toMethod != null) {
                    if (methods.containsKey(toMethod)) {
                        methods.get(toMethod).process(subject, respObj, subjectFld, convertObj.getObject(subjectFld));
                    } else {
                        throw new ApiException(415, String.format("Method <%s> for Field <%s> Does Not Exist", toMethod, subjectFld));
                    }
                }
            } else if (includeAll) {
                if ("orderBy".equals(subjectFld)) {
                    respObj.createStringArray(subjectFld);

                    for (var orderEntry : subject.getStringArray("orderBy")) {
                        boolean minusExists = false;
                        String lclOrderEntry = orderEntry;
                        String replaceEntry;

                        if (orderEntry.startsWith("-")) {
                            lclOrderEntry = orderEntry.substring(1);
                            minusExists = true;
                        }

                        if (convertObj.getType(lclOrderEntry) == ApiObject.TYPE_OBJECT) {
                            replaceEntry = convertObj.getString(lclOrderEntry + FIELD_DOT_FIELD);
                        } else {
                            replaceEntry = convertObj.getString(lclOrderEntry);
                        }

                        if (minusExists) {
                            replaceEntry = "-" + replaceEntry;
                        }

                        respObj.getStringArray(subjectFld).add(replaceEntry);
                    }
                } else {
                    respObj.put(subjectFld, subject.get(subjectFld));
                }
            }
        }

        return respObj;
    }

    /**
     * Convert ApiObject with root list or Single using a Conversion Object
     *
     * <p>
     * NOTE: Iterates over convertObj instead of subject</p>
     *
     * @param subject The subject to perform the conversion on
     * @param convertObj Field Mapping used for Conversion
     * @param includeAll true/false Return ALL fields from subject
     *
     * @return The Converted Object
     *
     * @throws ApiException
     */
    public ApiObject convertObjectV2(ApiObject subject, ApiObject convertObj, boolean includeAll) throws ApiException {
        if (subject != null) {
            if (convertObj != null) {
                if (subject.containsKey(FIELD_ROOT)) {
                    ApiObject respObj = new ApiObject();

                    respObj.createList(FIELD_ROOT);

                    for (var entry : subject.getList(FIELD_ROOT)) {
                        respObj.getList(FIELD_ROOT).add(convertSingleV2(entry, convertObj, includeAll));
                    }

                    return respObj;
                } else {
                    return convertSingleV2(subject, convertObj, includeAll);
                }
            } else {
                return ApiObjectUtils.cloneApiObject(subject, null);
            }
        } else {
            return null;
        }
    }

    /**
     * Convert a single ApiObject using a Conversion Object
     *
     * @param subject The subject to perform the conversion on
     * @param convertObj Field Mapping used for Conversion
     * @param includeAll true/false Return ALL fields from subject
     *
     * @return The Converted Object
     *
     * @throws ApiException
     */
    private ApiObject convertSingleV2(ApiObject subject, ApiObject convertObj, boolean includeAll) throws ApiException {
        ApiObject respObj = new ApiObject();

        for (String convertFld : convertObj.keySet()) {
            if (convertObj.getType(convertFld) == ApiObject.TYPE_STRING) {
                setField(respObj, convertObj.getString(convertFld), subject.get(convertFld));
            } else if (convertObj.getType(convertFld) == ApiObject.TYPE_OBJECT) {
                ApiObject convert = convertObj.getObject(convertFld);
                String intFld = convertFld; // Internal Field for use if # is present

                if (intFld.contains("#")) {
                    intFld = intFld.substring(0, intFld.indexOf("#"));
                }

                var toMethod = convert.getString(FIELD_METHOD);

                if (convert.isSet("default") && subject.getType(intFld) == ApiObject.TYPE_NULL) {
                    subject.put(intFld, convert.get("default"));
                }

                if (convert.isSet("required") && subject.isNull(intFld)) {
                    throw new ApiException(415, intFld + " IS Required");
                }

                if (convert.isSet(FIELD_TYPE) && !validateFieldType(intFld, convert.getString(FIELD_TYPE), subject)) {
                    throw new ApiException(415, intFld + " Expected Type " + convert.getString(FIELD_TYPE));
                }

                if (toMethod != null) {
                    if (methods.containsKey(toMethod)) {
                        methods.get(toMethod).process(subject, respObj, intFld, convert);
                    } else {
                        throw new ApiException(415, String.format("Method <%s> for Field <%s> Does Not Exist", toMethod, intFld));
                    }
                } else {
                    if (convert.getString(FIELD_FIELD).contains(".")) {
                        String[] fldSplit = convert.getString(FIELD_FIELD).split("\\.");

                        ApiObject objSub = respObj.getObject(fldSplit[0]);

                        if (objSub == null) {
                            objSub = respObj.createObject(fldSplit[0]);
                        }

                        objSub.put(fldSplit[1], subject.get(intFld));
                    } else {
                        respObj.put(convert.getString(FIELD_FIELD), subject.get(intFld));
                    }
                }
            } else if (includeAll) {
                if ("orderBy".equals(convertFld)) {
                    respObj.createStringArray(convertFld);

                    for (var orderEntry : subject.getStringArray("orderBy")) {
                        boolean minusExists = false;
                        String lclOrderEntry = orderEntry;
                        String replaceEntry;

                        if (orderEntry.startsWith("-")) {
                            lclOrderEntry = orderEntry.substring(1);
                            minusExists = true;
                        }

                        if (convertObj.getType(lclOrderEntry) == ApiObject.TYPE_OBJECT) {
                            replaceEntry = convertObj.getString(lclOrderEntry + FIELD_DOT_FIELD);
                        } else {
                            replaceEntry = convertObj.getString(lclOrderEntry);
                        }

                        if (minusExists) {
                            replaceEntry = "-" + replaceEntry;
                        }

                        respObj.getStringArray(convertFld).add(replaceEntry);
                    }
                } else {
                    respObj.put(convertFld, subject.get(convertFld));
                }
            }
        }

        return respObj;
    }

    private boolean validateFieldType(String fld, String typeString, ApiObject subject) {
        return switch (typeString) {
            case "string" ->
                subject.getType(fld) == ApiObject.TYPE_STRING;
            case "integer" ->
                subject.getType(fld) == ApiObject.TYPE_INTEGER;
            case "long" ->
                subject.getType(fld) == ApiObject.TYPE_LONG;
            case "object" ->
                subject.getType(fld) == ApiObject.TYPE_OBJECT;
            case "boolean" ->
                subject.getType(fld) == ApiObject.TYPE_BOOLEAN;
            case "arraylist" ->
                subject.getType(fld) == ApiObject.TYPE_ARRAYLIST;
            case "stringarray" ->
                subject.getType(fld) == ApiObject.TYPE_STRINGARRAY;

            default ->
                false;
        };
    }

    /**
     * Process object notation or set field
     * 
     * @param to Object to write to
     * @param fieldName Process dot notation for objects, or standard fields
     * @param value the value to sets
     */
    public void setField(ApiObject to, String fieldName, Object value) {
        if (fieldName.contains(".")) {
            String[] sName = fieldName.split("\\.");

            if (to.getType(sName[0]) != ApiObject.TYPE_OBJECT) {
                to.createObject(sName[0]);
            }

            to.getObject(sName[0]).put(sName[1], value);
        } else {
            to.put(fieldName, value);
        }
    }

    private String parseObjectToJson(ApiObject subject) {
        try {
            return jsonWriter.writeSingle(subject);
        } catch (ApiException apx) {
            log.error("Error Parsing ApiObject", apx);
            return null;
        }
    }

    private ApiObject parseJsonToObject(String subject) {
        try {
            return jsonParser.parseSingle(new StringReader(subject));
        } catch (ApiException | ApiClassNotFoundException apx) {
            log.error("Error Parsing ApiObject", apx);
            return null;
        }
    }

    /**
     * Convert Object to JSON String
     *
     * @param from
     * @param to
     * @param fieldFrom
     * @param processObj
     */
    private void json2String(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        if (from.getType(fieldFrom) != ApiObject.TYPE_NULL) {
            if (from.getType(fieldFrom) == ApiObject.TYPE_OBJECT) {
                setField(to, processObj.getString(FIELD_FIELD), parseObjectToJson(from.getObject(fieldFrom)));
            } else {
                throw new ApiException(410, String.format("Field<%s>: MUST be an Object", fieldFrom));
            }
        }
    }

    /**
     * Convert JSON String to Object
     *
     * @param from
     * @param to
     * @param fieldFrom
     * @param processObj
     * @throws ApiException
     */
    private void string2Json(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        if (from.getType(fieldFrom) != ApiObject.TYPE_NULL) {
            if (from.getType(fieldFrom) == ApiObject.TYPE_STRING) {
                setField(to, processObj.getString(FIELD_FIELD), parseJsonToObject(from.getString(fieldFrom)));
            } else {
                throw new ApiException(410, String.format("Field<%s>: MUST be a String", fieldFrom));
            }
        }
    }

    private void convertFieldToBoolean(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        switch (from.getType(fieldFrom)) {
            case ApiObject.TYPE_INTEGER -> {
                Boolean bTest = false;

                if (0 != from.getInteger(fieldFrom)) {
                    bTest = true;
                }

                setField(to, processObj.getString(FIELD_FIELD), bTest);
            }

            case ApiObject.TYPE_STRING -> {
                if ("true".equals(from.getString(fieldFrom))) {
                    setField(to, processObj.getString(FIELD_FIELD), true);
                } else {
                    setField(to, processObj.getString(FIELD_FIELD), false);
                }
            }

            default -> {
            }
        }
    }

    private void convertFieldFromBoolean(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        if (from.containsKey(fieldFrom) && from.getType(fieldFrom) == ApiObject.TYPE_BOOLEAN) {
            setField(to, processObj.getString(FIELD_FIELD), from.isSet(fieldFrom) ? 1 : 0);
        }
    }

    private void convertStringToDate(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        if (from.getType(fieldFrom) == ApiObject.TYPE_STRING) {
            String strFrom = from.getString(fieldFrom);

            if (strFrom.length() == 10) {
                // Expected format 2025-05-02 yyyy-MM-dd add rest of string
                strFrom += "T00:00:00.000Z";
            }

            try {
                OffsetDateTime dteFrom = OffsetDateTime.parse(strFrom);

                setField(to, processObj.getString(FIELD_FIELD), dteFrom);
            } catch (DateTimeParseException dtp) {
                throw new ApiException(410, "Field (" + fieldFrom + ") Cannot Be Parsed to DateTime. Expected Format: yyyy-MM-ddTHH:mm:ss.SSSX");
            }
        }
    }

    private void convertDateToString(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        if (from.getType(fieldFrom) == ApiObject.TYPE_DATETIME) {
            setField(to, processObj.getString(FIELD_FIELD), from.getDateTime(fieldFrom).format(DateTimeFormatter.ISO_DATE_TIME));
        }
    }
}
