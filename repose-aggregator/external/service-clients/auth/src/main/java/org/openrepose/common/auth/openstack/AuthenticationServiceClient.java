package org.openrepose.common.auth.openstack;

import org.openrepose.common.auth.AuthGroup;
import org.openrepose.common.auth.AuthGroups;
import org.openrepose.common.auth.AuthServiceException;
import org.openrepose.common.auth.ResponseUnmarshaller;
import com.rackspace.docs.identity.api.ext.rax_ksgrp.v1.Group;
import com.rackspace.docs.identity.api.ext.rax_ksgrp.v1.Groups;
import org.openrepose.commons.utils.StringUtilities;
import org.openrepose.commons.utils.http.HttpStatusCode;
import org.openrepose.commons.utils.http.ServiceClientResponse;
import org.openrepose.commons.utils.transform.jaxb.JaxbEntityToXml;
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClient;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.openstack.docs.identity.api.v2.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.xml.bind.JAXBElement;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class hosts the interaction between Repose and an OpenStack Identity Endpoint.
 */
public class AuthenticationServiceClient implements AuthenticationService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationServiceClient.class);
    private static final String AUTH_TOKEN_HEADER = "X-Auth-Token";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String TOKENS = "/tokens/";
    public static final String ENDPOINTS = "/endpoints";
    private final String targetHostUri;
    private final ResponseUnmarshaller openStackCoreResponseUnmarshaller;
    private final ResponseUnmarshaller openStackGroupsResponseUnmarshaller;
    private static final String TOKEN_PREFIX = "TOKEN:";
    private static final String GROUPS_PREFIX = "GROUPS:";
    private static final String ENDPOINTS_PREFIX = "ENDPOINTS";

    private AdminToken currentAdminToken;
    private final String requestBody;
    private final AkkaServiceClient akkaServiceClient;

    private static final ThreadLocal<String> delegationMessage = new ThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return "Authentication Service Client failure.";
        }
    };

    public AuthenticationServiceClient(String targetHostUri, String username, String password, String tenantId,
                                       ResponseUnmarshaller openStackCoreResponseUnmarshaller,
                                       ResponseUnmarshaller openStackGroupsResponseUnmarshaller,
                                       JaxbEntityToXml jaxbToString,
                                       AkkaServiceClient akkaServiceClient) {
        this.openStackCoreResponseUnmarshaller = openStackCoreResponseUnmarshaller;
        this.openStackGroupsResponseUnmarshaller = openStackGroupsResponseUnmarshaller;
        this.targetHostUri = targetHostUri;
        this.akkaServiceClient = akkaServiceClient;

        ObjectFactory objectFactory = new ObjectFactory();
        PasswordCredentialsRequiredUsername credentials = new PasswordCredentialsRequiredUsername();
        credentials.setUsername(username);
        credentials.setPassword(password);

        JAXBElement jaxbCredentials = objectFactory.createPasswordCredentials(credentials);

        AuthenticationRequest request = new AuthenticationRequest();

        if (!StringUtilities.isBlank(tenantId)) {
            request.setTenantId(tenantId);
        }

        request.setCredential(jaxbCredentials);

        JAXBElement jaxbRequest = objectFactory.createAuth(request);
        requestBody = jaxbToString.transform(jaxbRequest);
    }

    @Override
    public AuthenticateResponse validateToken(String tenant, String userToken) { //this is where we ask auth service if token is valid

        AuthenticateResponse authenticateResponse = null;
        ServiceClientResponse serviceResponse = validateUser(userToken, tenant, false);

        switch (HttpStatusCode.fromInt(serviceResponse.getStatusCode())) {
            case OK:
                authenticateResponse = openStackCoreResponseUnmarshaller.unmarshall(serviceResponse.getData(), AuthenticateResponse.class);
                break;

            case NOT_FOUND:
                // User's token is bad
                delegationMessage.set("Unable to validate token: " + userToken + ". Invalid token.");
                LOG.error("Unable to validate token.  Invalid token. " + serviceResponse.getStatusCode());
                break;

            case UNAUTHORIZED:
                LOG.error("Unable to validate token: " + userToken + " due to status code: " + serviceResponse.getStatusCode()  + " :admin token expired. Retrieving new admin token and retrying token validation...");

                serviceResponse = validateUser(userToken, tenant, true);

                if (serviceResponse.getStatusCode() == HttpStatusCode.OK.intValue()) {
                    authenticateResponse = openStackCoreResponseUnmarshaller.unmarshall(serviceResponse.getData(), AuthenticateResponse.class);
                } else if (serviceResponse.getStatusCode() == HttpStatusCode.NOT_FOUND.intValue()) {
                    delegationMessage.set("Unable to validate token: " + userToken + ". Invalid token. Status Code: " + serviceResponse.getStatusCode());
                    LOG.error("Unable to validate token.  Invalid token. " + serviceResponse.getStatusCode());
                } else {
                    delegationMessage.set("Unable to validate token: " + userToken + " with configured admin credentials.");
                    LOG.error("Still unable to validate token: " + serviceResponse.getStatusCode());
                    throw new AuthServiceException("Unable to authenticate user with configured Admin credentials");
                }
                break;


            default:
                delegationMessage.set("Authentication Service returned an unexpected response status code: " + serviceResponse.getStatusCode() + " for token: " + userToken);
                LOG.error("Authentication Service returned an unexpected response status code: " + serviceResponse.getStatusCode());
                throw new AuthServiceException("Unable to validate token. Response from " + targetHostUri + ": " + serviceResponse.getStatusCode());
        }

        return authenticateResponse;
    }

    private ServiceClientResponse validateUser(String userToken, String tenant, boolean force) {
        final Map<String, String> headers = new HashMap<>();
        headers.put(ACCEPT_HEADER, MediaType.APPLICATION_XML);
        headers.put(AUTH_TOKEN_HEADER, getAdminToken(force));
        return akkaServiceClient.get(TOKEN_PREFIX + userToken, targetHostUri + TOKENS + userToken, headers);
    }

    @Override
    public List<Endpoint> getEndpointsForToken(String userToken) {
        final Map<String, String> headers = new HashMap<>();

        headers.put(ACCEPT_HEADER, MediaType.APPLICATION_XML);


        headers.put(AUTH_TOKEN_HEADER, getAdminToken(false));

        ServiceClientResponse endpointListResponse = akkaServiceClient.get(ENDPOINTS_PREFIX + userToken, targetHostUri + TOKENS + userToken +
                ENDPOINTS, headers);
        List<Endpoint> endpointList;

        switch (HttpStatusCode.fromInt(endpointListResponse.getStatusCode())) {
            case OK:
                endpointList = getEndpointList(endpointListResponse);

                break;
            case UNAUTHORIZED:
                LOG.error("Unable to get endpoints for user: " + endpointListResponse.getStatusCode() + " :admin token expired. " +
                        "Retrieving new admin token and retrying endpoints retrieval...");

                headers.put(AUTH_TOKEN_HEADER, getAdminToken(true));
                endpointListResponse = akkaServiceClient.get(ENDPOINTS_PREFIX + userToken, targetHostUri + TOKENS + userToken + ENDPOINTS, headers);

                if (endpointListResponse.getStatusCode() == HttpStatusCode.OK.intValue()) {
                    endpointList = getEndpointList(endpointListResponse);
                } else {
                    delegationMessage.set("Unable to get endpoints for user: " + userToken + " with configured admin credentials");
                    LOG.error("Still unable to get endpoints: " + endpointListResponse.getStatusCode());
                    throw new AuthServiceException("Unable to retrieve service catalog for user with configured Admin credentials");
                }
                break;
            default:
                delegationMessage.set("Unable to get endpoints for token: " + userToken + ". Status code: " + endpointListResponse.getStatusCode());
                LOG.error("Unable to get endpoints for token. Status code: " + endpointListResponse.getStatusCode());
                throw new AuthServiceException("Unable to retrieve service catalog for user. Response from " + targetHostUri + ": " + endpointListResponse.getStatusCode());

        }

        return endpointList;
    }

    // Method to take in the format and token, then use that info to get the endpoints catalog from auth, and return it encoded.
    @Override
    public String getBase64EndpointsStringForHeaders(String userToken, String format) {
        final Map<String, String> headers = new HashMap<>();

        //defaulting to json format
        if ("xml".equalsIgnoreCase(format)) {
            format = MediaType.APPLICATION_XML;
        } else {
            format = MediaType.APPLICATION_JSON;
        }

        //telling the service what format to send the endpoints to us in
        headers.put(ACCEPT_HEADER, format);
        headers.put(AUTH_TOKEN_HEADER, getAdminToken(false));


        ServiceClientResponse serviceClientResponse = akkaServiceClient.get(ENDPOINTS_PREFIX + userToken, targetHostUri + TOKENS + userToken + ENDPOINTS, headers);

        String rawEndpointsData;

        switch (HttpStatusCode.fromInt(serviceClientResponse.getStatusCode())) {
            case OK:
                rawEndpointsData = convertStreamToBase64String(serviceClientResponse.getData());
                break;
            case UNAUTHORIZED:
                LOG.error("Unable to get endpoints for user: " + serviceClientResponse.getStatusCode() + " :admin token expired. Retrieving new admin token and retrying endpoints retrieval...");

                headers.put(AUTH_TOKEN_HEADER, getAdminToken(true));
                serviceClientResponse = akkaServiceClient.get(ENDPOINTS_PREFIX + userToken, targetHostUri + TOKENS + userToken + ENDPOINTS, headers);

                if (serviceClientResponse.getStatusCode() == HttpStatusCode.ACCEPTED.intValue()) {
                    rawEndpointsData = convertStreamToBase64String(serviceClientResponse.getData());
                } else {
                    delegationMessage.set("Unable to get endpoints for user: " + userToken + " with configured admin credentials");
                    LOG.error("Still unable to get endpoints: " + serviceClientResponse.getStatusCode());
                    throw new AuthServiceException("Unable to retrieve service catalog for user with configured Admin credentials");
                }
                break;
            default:
                delegationMessage.set("Unable to get endpoints for token: " + userToken + ". Status code: " + serviceClientResponse.getStatusCode());
                LOG.error("Unable to get endpoints for token. Status code: " + serviceClientResponse.getStatusCode());
                throw new AuthServiceException("Unable to retrieve service catalog for user. Response from " + targetHostUri + ": " + serviceClientResponse.getStatusCode());

        }

        return rawEndpointsData;
    }

    private String convertStreamToBase64String(InputStream inputStream) {
        StringWriter stringWriter = new StringWriter();
        try {
            IOUtils.copy(inputStream, stringWriter, "UTF8");
        } catch (IOException e) {
            LOG.error("Unable to copy stream: " + e.getMessage(), e);
        }
        String stringFromStream = stringWriter.toString();
        byte[] encodedString = Base64.encodeBase64(stringFromStream.getBytes());
        return new String(encodedString);
    }

    private List<Endpoint> getEndpointList(ServiceClientResponse endpointListResponse) {
        List<Endpoint> endpointList = new ArrayList<>();

        final EndpointList unmarshalledEndpoints = openStackCoreResponseUnmarshaller.unmarshall(endpointListResponse.getData(), EndpointList.class);

        if (unmarshalledEndpoints != null) {
            endpointList = unmarshalledEndpoints.getEndpoint();
        }

        return endpointList;
    }

    @Override
    public AuthGroups getGroups(String userId) {
        final Map<String, String> headers = new HashMap<>();

        headers.put(ACCEPT_HEADER, MediaType.APPLICATION_XML);
        headers.put(AUTH_TOKEN_HEADER, getAdminToken(false));


        ServiceClientResponse serviceResponse = akkaServiceClient.get(GROUPS_PREFIX + userId, targetHostUri + "/users/" + userId + "/RAX-KSGRP", headers);
        AuthGroups authGroups;

        switch (HttpStatusCode.fromInt(serviceResponse.getStatusCode())) {
            case OK:
                authGroups = getAuthGroups(serviceResponse);
                break;
            case UNAUTHORIZED:
                LOG.error("Unable to get groups for user: " + serviceResponse.getStatusCode() + " :admin token expired. Retrieving new admin token and retrying groups retrieval...");

                headers.put(AUTH_TOKEN_HEADER, getAdminToken(true));

                serviceResponse = akkaServiceClient.get(GROUPS_PREFIX + userId, targetHostUri + "/users/" + userId + "/RAX-KSGRP", headers);

                if (serviceResponse.getStatusCode() == HttpStatusCode.ACCEPTED.intValue()) {
                    authGroups = getAuthGroups(serviceResponse);
                } else {
                    delegationMessage.set("Unable to get groups for user id: " + userId + ". Status code: " + serviceResponse.getStatusCode());
                    LOG.error("Still unable to get groups: " + serviceResponse.getStatusCode());
                    throw new AuthServiceException("Unable to retrieve groups for user. Response from " + targetHostUri + ": " + serviceResponse.getStatusCode());

                }
                break;
            default:
                delegationMessage.set("Unable to get groups for user id: " + userId + ". Status code: " + serviceResponse.getStatusCode());
                LOG.error("Unable to get groups for user id: " + userId + " Status code: " + serviceResponse.getStatusCode());
                throw new AuthServiceException("Unable to retrieve groups for user. Response from " + targetHostUri + ": " + serviceResponse.getStatusCode());
        }

        return authGroups;
    }

    private AuthGroups getAuthGroups(ServiceClientResponse serviceResponse) {
        final List<AuthGroup> authGroupList = new ArrayList<>();
        final Groups groups = openStackGroupsResponseUnmarshaller.unmarshall(serviceResponse.getData(), Groups.class);

        if (groups != null) {
            for (Group group : groups.getGroup()) {
                final AuthGroup authGroup = new OpenStackGroup(group);
                authGroupList.add(authGroup);
            }

            return new AuthGroups(authGroupList);
        } else {
            LOG.warn("Response unmarshaller returned null groups.");


            return null;
        }
    }

    private String getAdminToken(boolean force) {

        String adminToken = !force && currentAdminToken != null && currentAdminToken.isValid() ? currentAdminToken.getToken() : null;

        if (adminToken == null) {
            final ServiceClientResponse serviceResponse = akkaServiceClient.post(AdminToken.CACHE_KEY,
                    targetHostUri + "/tokens",
                    new HashMap<String, String>(),
                    requestBody,
                    MediaType.APPLICATION_XML_TYPE);

            switch (HttpStatusCode.fromInt(serviceResponse.getStatusCode())) {
                case OK:
                    final AuthenticateResponse authenticateResponse = openStackCoreResponseUnmarshaller.unmarshall(serviceResponse.getData(), AuthenticateResponse.class);

                    Token token = authenticateResponse.getToken();
                    currentAdminToken = new AdminToken(token.getId(), token.getExpires().toGregorianCalendar());
                    adminToken = currentAdminToken.getToken();
                    break;

                default:
                    delegationMessage.set("Unable to get admin token. Status code: " + serviceResponse.getStatusCode());
                    LOG.error("Unable to get admin token.  Verify admin credentials. " + serviceResponse.getStatusCode());
                    currentAdminToken = null;
                    throw new AuthServiceException("Unable to retrieve admin token ");

            }
        }

        return adminToken;
    }

    public static String getDelegationMessage() {
        return delegationMessage.get();
    }

    public static void removeDelegationMessage() {
        delegationMessage.remove();
    }
}