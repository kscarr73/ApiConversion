package com.progbits.api.conversion;

import com.progbits.api.exception.ApiClassNotFoundException;
import com.progbits.api.exception.ApiException;
import com.progbits.api.model.ApiObject;
import com.progbits.api.parser.JsonObjectParser;
import com.progbits.api.writer.JsonObjectWriter;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convert from one ApiObject to Another using a Config Object
 *
 * @author Kevin.Carr
 */
public class ApiObjectConverter {

    private static final Logger log = LoggerFactory.getLogger(ApiObjectConverter.class);

    private static ApiObjectConverter instance;
    private static ReentrantLock lock;
    private final CountDownLatch configured = new CountDownLatch(1);

    public static ApiObjectConverter getInstance() {
        if (lock == null) {
            lock = new ReentrantLock();
        }

        if (instance == null) {
            lock.lock();

            try {
                if (instance == null) {
                    instance = new ApiObjectConverter();

                    instance.configure();
                }
            } finally {
                lock.unlock();
            }
        }

        try {
            instance.configured.await();
        } catch (InterruptedException ex) {
            // Nothing to report
        }

        return instance;
    }

    public void configure() {
        try {
            methods.put("json2String", this::json2String);
            methods.put("string2Json", this::string2Json);

            methods.put("boolToInt", this::convertFieldFromBoolean);
            methods.put("intToBool", this::convertFieldToBoolean);
        
            reverseMethods.setString("json2String", "string2Json");
            reverseMethods.setString("string2Json", "json2String");
            reverseMethods.setString("boolToInt", "intToBool");
            reverseMethods.setString("intToBool", "boolToInt");
        } finally {
            configured.countDown();
        }
    }

    private Map<String, ApiMethodHandler> methods = new HashMap<>();

    private static final JsonObjectParser jsonParser = new JsonObjectParser(true);
    private static final JsonObjectWriter jsonWriter = new JsonObjectWriter(true);
    /*
      Reverse Methods are used when converting a Conversion Config object
     */
    private ApiObject reverseMethods = new ApiObject();
    
    public ApiObject getReverseMethods() {
        return reverseMethods;
    }

    public Map<String, ApiMethodHandler> getMethods() {
        return methods;
    }

    public ApiObject reverseConvertObject(ApiObject convertObj) {
        ApiObject respObj = new ApiObject();

        for (var field : convertObj.keySet()) {
            if (convertObj.getType(field) == ApiObject.TYPE_STRING) {
                respObj.put(convertObj.getString(field), field);
            } else if (convertObj.getType(field) == ApiObject.TYPE_OBJECT) {
                ApiObject objField = new ApiObject();

                objField.putAll(convertObj.getObject(field));

                objField.setString("field", field);
                objField.setString("method", reverseMethods.getString(convertObj.getString(field + ".method")));

                respObj.setObject(convertObj.getString(field + ".field"), objField);
            }
        }

        return respObj;
    }

    private static final String FIELD_ROOT = "root";
    
    public ApiObject convertObject(ApiObject subject, ApiObject convertObj) throws ApiException {
        return convertObject(subject, convertObj, false);
    }
    
    /**
     * Convert an ApiObject using a Conversion Object
     *
     * @param subject The subject to perform the conversion on
     * @param convertObj Field Mapping used for Conversion
     * @return The Converted Object
     *
     * @throws ApiException
     */
    public ApiObject convertObject(ApiObject subject, ApiObject convertObj, boolean includeAll) throws ApiException {
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
    }
    
    public ApiObject convertSingle(ApiObject subject, ApiObject convertObj, boolean includeAll) throws ApiException {
        ApiObject respObj = new ApiObject();

        for (var subjectFld : subject.keySet()) {
            if (convertObj.getType(subjectFld) == ApiObject.TYPE_STRING) {
                respObj.put(convertObj.getString(subjectFld), subject.get(subjectFld));
            } else if (convertObj.getType(subjectFld) == ApiObject.TYPE_OBJECT) {
                var toField = convertObj.getString(subjectFld + ".field");
                var toMethod = convertObj.getString(subjectFld + ".method");

                if (methods.containsKey(toMethod)) {
                    methods.get(toMethod).process(subject, respObj, subjectFld, convertObj.getObject(subjectFld));
                } else {
                    throw new ApiException(415, String.format("Method <%s> for Field <%s> Does Not Exist", toMethod, subjectFld));
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
                            replaceEntry = convertObj.getString(lclOrderEntry + ".field");
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
                to.setString(processObj.getString("field"), parseObjectToJson(from.getObject(fieldFrom)));
            } else {
                throw new ApiException(410, String.format("Field<%s>: MUST be an Object", fieldFrom));
            }
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
                to.setObject(processObj.getString("field"), parseJsonToObject(from.getString(fieldFrom)));
            } else {
                throw new ApiException(410, String.format("Field<%s>: MUST be a String", fieldFrom));
            }
        }
    }

    private void convertFieldToBoolean(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        if (from.getType(fieldFrom) == ApiObject.TYPE_INTEGER) {
            Boolean bTest = false;

            if (0 != from.getInteger(fieldFrom)) {
                bTest = true;
            }

            to.setBoolean(fieldFrom, bTest);
        }
    }

    private void convertFieldFromBoolean(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        if (from.containsKey(fieldFrom)) {
            to.setInteger(fieldFrom, Integer.valueOf(from.isSet(fieldFrom) ? 1 : 0));
        }
    }
}
