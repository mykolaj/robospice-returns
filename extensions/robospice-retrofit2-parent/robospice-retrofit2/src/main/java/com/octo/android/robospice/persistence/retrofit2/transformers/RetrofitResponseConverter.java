package com.octo.android.robospice.persistence.retrofit2.transformers;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

public interface RetrofitResponseConverter {
    Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    void saveObject(Object object, Class<?> clzz, OutputStream out) throws Exception;
    Object restoreObject(InputStream in, Class<?> clzz) throws Exception;

}
