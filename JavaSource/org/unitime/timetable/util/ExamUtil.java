package org.unitime.timetable.util;

import jakarta.servlet.http.HttpServletRequest;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Enumeration;

import static org.apache.struts2.ServletActionContext.getRequest;

public class ExamUtil {
    public static String buildRedirectUrl(String baseUrl, HttpServletRequest request)
            throws UnsupportedEncodingException {

        StringBuilder url = new StringBuilder(baseUrl);
        boolean first = true;

        for (Enumeration<String> e = request.getParameterNames(); e.hasMoreElements(); ) {
            String param = e.nextElement();
            url.append(first ? "?" : "&")
                    .append(param)
                    .append("=")
                    .append(URLEncoder.encode(request.getParameter(param), "utf-8"));
            first = false;
        }

        return url.toString();
    }
}
