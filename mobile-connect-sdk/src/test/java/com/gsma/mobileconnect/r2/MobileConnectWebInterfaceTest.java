/*
 * SOFTWARE USE PERMISSION
 *
 * By downloading and accessing this software and associated documentation files ("Software") you are granted the
 * unrestricted right to deal in the Software, including, without limitation the right to use, copy, modify, publish,
 * sublicense and grant such rights to third parties, subject to the following conditions:
 *
 * The following copyright notice and this permission notice shall be included in all copies, modifications or
 * substantial portions of this Software: Copyright © 2016 GSM Association.
 *
 * THE SOFTWARE IS PROVIDED "AS IS," WITHOUT WARRANTY OF ANY KIND, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE. YOU AGREE TO
 * INDEMNIFY AND HOLD HARMLESS THE AUTHORS AND COPYRIGHT HOLDERS FROM AND AGAINST ANY SUCH LIABILITY.
 */
package com.gsma.mobileconnect.r2;

import com.gsma.mobileconnect.r2.authentication.AuthenticationOptions;
import com.gsma.mobileconnect.r2.cache.CacheAccessException;
import com.gsma.mobileconnect.r2.discovery.DiscoveryResponse;
import com.gsma.mobileconnect.r2.discovery.IDiscoveryService;
import com.gsma.mobileconnect.r2.json.IJsonService;
import com.gsma.mobileconnect.r2.json.JacksonJsonService;
import com.gsma.mobileconnect.r2.json.JsonDeserializationException;
import com.gsma.mobileconnect.r2.rest.MockRestClient;
import com.gsma.mobileconnect.r2.rest.RequestFailedException;
import com.gsma.mobileconnect.r2.utils.HttpUtils;
import com.gsma.mobileconnect.r2.utils.TestUtils;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.*;

/**
 * Tests {@link MobileConnectWebInterface}
 *
 * @since 2.0
 */
public class MobileConnectWebInterfaceTest
{
    private final MobileConnectConfig config = new MobileConnectConfig.Builder()
        .withClientId("zxcvbnm")
        .withClientSecret("asdfghjkl")
        .withDiscoveryUrl(URI.create("http://discovery/test"))
        .withRedirectUrl(URI.create("http://redirect/test"))
        .build();
    private final MockRestClient restClient = new MockRestClient();

    private final MobileConnect mobileConnect =
        MobileConnect.builder(this.config).withRestClient(this.restClient).build();

    private final IJsonService jsonService = new JacksonJsonService();
    private final IDiscoveryService discoveryService = this.mobileConnect.getDiscoveryService();
    private final MobileConnectWebInterface mcWebInterface =
        this.mobileConnect.getMobileConnectWebInterface();

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private DiscoveryResponse completeDiscovery()
        throws RequestFailedException, InvalidResponseException
    {
        this.restClient
            .addResponse(TestUtils.AUTHENTICATION_RESPONSE)
            .addResponse(TestUtils.PROVIDER_METADATA_RESPONSE);

        return this.discoveryService.completeSelectedOperatorDiscovery(this.config,
            this.config.getRedirectUrl(), "111", "11");
    }

    @BeforeMethod
    public void beforeMethod() throws CacheAccessException
    {
        this.discoveryService.getCache().clear();
    }

    @AfterMethod
    public void afterMethod()
    {
        assertEquals(this.restClient.reset().size(), 0);
    }

    @DataProvider
    public Object[][] startAuthnData()
    {
        return new Object[][] {
            //
            {null, new String[] {"mc_authn"}, "mc_authz"},
            //
            {new AuthenticationOptions.Builder().withContext("context").build(),
             new String[] {"mc_authz"}, "mc_authn"},
            //
            {new AuthenticationOptions.Builder()
                 .withScope("mc_authz")
                 .withContext("context")
                 .withBindingMessage("message").build(), new String[] {"mc_authz"}, "mc_authn"},
            //
            {new AuthenticationOptions.Builder()
                 .withScope("mc_identity_phone")
                 .withContext("context")
                 .withBindingMessage("message").build(),
             new String[] {"mc_authz", "mc_identity_phone"}, "mc_authn"}};
    }

    @Test(dataProvider = "startAuthnData")
    public void startAuthenticationScopes(final AuthenticationOptions authnOptions,
        final String[] includes, final String exclude)
        throws RequestFailedException, InvalidResponseException
    {
        final DiscoveryResponse discoveryResponse = this.completeDiscovery();

        final MobileConnectRequestOptions options = authnOptions == null
                                                    ? null
                                                    : new MobileConnectRequestOptions.Builder()
                                                        .withAuthenticationOptions(authnOptions)
                                                        .build();

        final MobileConnectStatus status =
            this.mcWebInterface.startAuthentication(this.request, discoveryResponse,
                "1111222233334444", "state", "nonce", options);

        assertNotNull(status);
        assertEquals(status.getResponseType(), MobileConnectStatus.ResponseType.AUTHENTICATION);

        final String scope = HttpUtils.extractQueryValue(URI.create(status.getUrl()), "scope");

        for (final String include : includes)
        {
            assertTrue(scope.contains(include));
        }
        assertFalse(scope.contains(exclude));
    }

    @Test
    public void startAuthenticationShouldSetClientNameWhenAuthz()
        throws RequestFailedException, InvalidResponseException
    {
        final DiscoveryResponse discoveryResponse = this.completeDiscovery();

        final MobileConnectRequestOptions options = new MobileConnectRequestOptions.Builder()
            .withAuthenticationOptions(new AuthenticationOptions.Builder()
                .withScope("mc_identity_phone")
                .withContext("context")
                .withBindingMessage("message")
                .build())
            .build();

        final MobileConnectStatus status =
            this.mcWebInterface.startAuthentication(this.request, discoveryResponse,
                "1111222233334444", "state", "nonce", options);

        final String clientName =
            HttpUtils.extractQueryValue(URI.create(status.getUrl()), "client_name");

        assertEquals(clientName, "test1"); // set in the response under TestUtils
    }

    @Test
    public void requestUserInfoReturnsUserInfo() throws JsonDeserializationException
    {
        this.restClient.addResponse(TestUtils.USERINFO_RESPONSE);
        final DiscoveryResponse discoveryResponse =
            DiscoveryResponse.fromRestResponse(TestUtils.AUTHENTICATION_RESPONSE, this.jsonService);

        final MobileConnectStatus status =
            this.mcWebInterface.requestUserInfo(this.request, discoveryResponse,
                "zaqwsxcderfvbgtyhnmjukilop", null);

        assertEquals(status.getResponseType(), MobileConnectStatus.ResponseType.USER_INFO);
        assertNotNull(status.getIdentityResponse());
    }

    @Test
    public void requestUserInfoReturnsErrorWhenNoUserInfoUrl() throws JsonDeserializationException
    {
        final DiscoveryResponse discoveryResponse =
            DiscoveryResponse.fromRestResponse(TestUtils.AUTHENTICATION_NO_URI_RESPONSE,
                this.jsonService);

        final MobileConnectStatus status =
            this.mcWebInterface.requestUserInfo(this.request, discoveryResponse,
                "zaqwsxcderfvbgtyhnmjukilop", null);

        assertEquals(status.getResponseType(), MobileConnectStatus.ResponseType.ERROR);
        assertNull(status.getIdentityResponse());
        assertNotNull(status.getErrorCode());
        assertNotNull(status.getErrorMessage());
    }

    @Test
    public void requestUserInfoShouldUseSdkSessionCache()
        throws JsonDeserializationException, CacheAccessException
    {
        this.restClient.addResponse(TestUtils.USERINFO_RESPONSE);

        final DiscoveryResponse discoveryResponse =
            DiscoveryResponse.fromRestResponse(TestUtils.AUTHENTICATION_RESPONSE, this.jsonService);
        this.discoveryService.getCache().add("sessionid", discoveryResponse);

        final MobileConnectStatus status =
            this.mcWebInterface.requestUserInfo(this.request, "sessionid",
                "zaqwsxcderfvbgtyhnmjukilop", null);

        assertEquals(status.getResponseType(), MobileConnectStatus.ResponseType.USER_INFO);
        assertNotNull(status.getIdentityResponse());
    }

    @Test
    public void requestTokenShouldReturnErrorForInvalidSession()
    {
        final MobileConnectStatus status =
            this.mcWebInterface.requestToken(this.request, "invalidid", URI.create("http://test"),
                "state", "nonce", null);

        assertEquals(status.getResponseType(), MobileConnectStatus.ResponseType.ERROR);
        assertEquals(status.getErrorCode(), "sdksession_not_found");
    }

    @Test
    public void requestTokenShouldReturnErrorForCacheDisabled()
    {
        final MobileConnectConfig config = new MobileConnectConfig.Builder()
            .withCacheResponsesWithSessionId(false)
            .withClientId("id")
            .withClientSecret("secret")
            .withDiscoveryUrl(URI.create("http://discovery"))
            .withRedirectUrl(URI.create("http://redirect"))
            .build();

        final MobileConnectWebInterface mcWebInterface = MobileConnect.buildWebInterface(config);

        final MobileConnectStatus status =
            mcWebInterface.requestToken(this.request, "invalidid", URI.create("http://test"),
                "state", "nonce", null);

        assertEquals(status.getResponseType(), MobileConnectStatus.ResponseType.ERROR);
        assertEquals(status.getErrorCode(), "cache_disabled");
    }
}
