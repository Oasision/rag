package com.huangyifei.rag.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.Hibernate;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

public class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtil() {
    }

    public static String toStr(Object value) {
        try {
            Object normalized = value == null ? null : Hibernate.unproxy(value);
            return MAPPER.writeValueAsString(normalized);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> List<T> toList(String value, Class<T> clazz) {
        try {
            return MAPPER.readValue(value, MAPPER.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T toObj(String value, Class<T> clazz) {
        try {
            return MAPPER.readValue(value, clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T toObj(String value, Type type) {
        try {
            return MAPPER.readValue(value, new TypeReference<>() {
                @Override
                public Type getType() {
                    return type;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T toObj(String value, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(value, typeReference);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
