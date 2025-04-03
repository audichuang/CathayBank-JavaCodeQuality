package com.cathaybk.codingassistant.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 用於標記API的消息ID
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface ApiMsgId {
    /**
     * API的消息ID
     */
    String value();

    /**
     * 額外描述
     */
    String description() default "";
}