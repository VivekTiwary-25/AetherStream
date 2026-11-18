package io.aetherstream.api.org;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Caller-supplied demo identity, not authentication. Never retained outside the request. */
public final class OrgContext {
    static final String ATTRIBUTE = OrgContext.class.getName() + ".org";

    private OrgContext() {}

    public static String current() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Object org = request == null ? null : request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return org == null ? "demo" : (String) org;
    }

    /** The coordinator's idempotency column is globally unique, so include the org unambiguously. */
    public static String idempotencyKey(String key) {
        String org = current();
        return org.length() + ":" + org + ":" + key;
    }

    public static ObjectNode detail(Object value) {
        ObjectNode detail = Json.MAPPER.valueToTree(value);
        return detail.put("orgId", current());
    }
}
