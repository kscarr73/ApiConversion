package com.progbits.api.conversion;

import com.progbits.api.exception.ApiException;
import com.progbits.api.model.ApiObject;

/**
 *
 * @author Kevin.Carr
 */
public interface ApiMethodHandler {
    void process(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException;
}
