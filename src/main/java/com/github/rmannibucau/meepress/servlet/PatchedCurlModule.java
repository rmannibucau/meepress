package com.github.rmannibucau.meepress.servlet;

import com.caucho.quercus.annotation.NotNull;
import com.caucho.quercus.env.BooleanValue;
import com.caucho.quercus.env.Env;
import com.caucho.quercus.env.Value;
import com.caucho.quercus.lib.curl.CurlModule;
import com.caucho.quercus.lib.curl.CurlResource;

public class PatchedCurlModule extends CurlModule {
    public static BooleanValue curl_setopt(final Env env,
                                           @NotNull final CurlResource curl,
                                           final int option,
                                           final Value value) {
        if ((option == CURLOPT_WRITEFUNCTION || option == CURLOPT_HEADERFUNCTION) && value.isNull()) { // avoid warnings
            return BooleanValue.FALSE;
        }
        if (option == CURLOPT_CAINFO) { // skip, let's use java one which works well
            return BooleanValue.FALSE;
        }
        return CurlModule.curl_setopt(env, curl, option, value);
    }
}
