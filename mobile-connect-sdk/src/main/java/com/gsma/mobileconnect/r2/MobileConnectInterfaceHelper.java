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
import com.gsma.mobileconnect.r2.authentication.IAuthenticationService;
import com.gsma.mobileconnect.r2.authentication.RequestTokenResponse;
import com.gsma.mobileconnect.r2.authentication.StartAuthenticationResponse;
import com.gsma.mobileconnect.r2.constants.Parameters;
import com.gsma.mobileconnect.r2.discovery.*;
import com.gsma.mobileconnect.r2.identity.IIdentityService;
import com.gsma.mobileconnect.r2.identity.IdentityResponse;
import com.gsma.mobileconnect.r2.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs interaction on behalf of {@link MobileConnectInterface}.
 *
 * @since 2.0
 */
class MobileConnectInterfaceHelper
{
    private static final Logger LOGGER =
        LoggerFactory.getLogger(MobileConnectInterfaceHelper.class);
    private static final Pattern NONCE_REGEX = Pattern.compile("\\\"?nonce\\\"?:\\\"(.*)\\\"");

    private MobileConnectInterfaceHelper()
    {
    }

    static MobileConnectStatus attemptDiscovery(final IDiscoveryService discoveryService,
        final String msisdn, final String mcc, final String mnc,
        final Iterable<KeyValuePair> cookies, final MobileConnectConfig config,
        final DiscoveryOptions.Builder discoveryOptionsBuilder)
    {
        try
        {
            discoveryOptionsBuilder
                .withMsisdn(msisdn)
                .withIdentifiedMcc(mcc)
                .withIdentifiedMnc(mnc)
                .withRedirectUrl(config.getRedirectUrl());

            final DiscoveryResponse response =
                discoveryService.startAutomatedOperatorDiscovery(config, config.getRedirectUrl(),
                    discoveryOptionsBuilder.build(), cookies);

            return extractStatus(response, discoveryService, "attemptDiscovery");
        }
        catch (final Exception e)
        {
            LOGGER.warn("attemptDiscovery failed for msisdn={}, mcc={}, mnc={}",
                LogUtils.mask(msisdn, LOGGER, Level.WARN), mcc, mnc, e);
            return MobileConnectStatus.error("start automated discovery", e);
        }
    }

    static MobileConnectStatus attemptDiscoveryAfterOperatorSelection(
        final IDiscoveryService discoveryService, final URI redirectedUrl,
        final MobileConnectConfig config)
    {
        final ParsedDiscoveryRedirect parsedDiscoveryRedirect =
            discoveryService.parseDiscoveryRedirect(redirectedUrl);

        if (!parsedDiscoveryRedirect.hasMccAndMnc())
        {
            LOGGER.debug(
                "Responding with responseType={} for attemptDiscoveryAfterOperatorSelection for redirectedUrl={}",
                MobileConnectStatus.ResponseType.START_DISCOVERY,
                LogUtils.maskUri(redirectedUrl, LOGGER, Level.DEBUG));
            return MobileConnectStatus.startDiscovery();
        }
        else
        {
            try
            {
                DiscoveryResponse response =
                    discoveryService.completeSelectedOperatorDiscovery(config,
                        config.getRedirectUrl(), parsedDiscoveryRedirect.getSelectedMcc(),
                        parsedDiscoveryRedirect.getSelectedMnc());

                if (response.getResponseData().getSubscriberId() == null)
                {
                    final String encryptedMsisdn = parsedDiscoveryRedirect.getEncryptedMsisdn();
                    LOGGER.debug(
                        "Setting encryptedMsisdn={} against cached DiscoveryResponse for redirectedUrl={}",
                        LogUtils.mask(encryptedMsisdn, LOGGER, Level.DEBUG),
                        LogUtils.maskUri(redirectedUrl, LOGGER, Level.DEBUG));
                    response = response.withSubscriberId(encryptedMsisdn);
                }

                return extractStatus(response, discoveryService,
                    "attemptDiscoveryAfterOperatorSelection");
            }
            catch (final Exception e)
            {
                LOGGER.warn("attemptDiscoveryAfterOperatorSelection failed for redirectedUrl={}",
                    LogUtils.maskUri(redirectedUrl, LOGGER, Level.WARN), e);
                return MobileConnectStatus.error("attempt discovery after operator selection", e);
            }
        }
    }

    static MobileConnectStatus startAuthentication(final IAuthenticationService authnService,
        final DiscoveryResponse discoveryResponse, final String encryptedMsisdn, final String state,
        final String nonce, final MobileConnectConfig config,
        final AuthenticationOptions.Builder authnOptionsBuilder)
    {
        ObjectUtils.requireNonNull(discoveryResponse, "discoveryResponse");

        try
        {
            final String clientId = ObjectUtils.defaultIfNull(
                discoveryResponse.getResponseData().getResponse().getClientId(),
                config.getClientId());
            final URI authorizationUrl =
                URI.create(discoveryResponse.getOperatorUrls().getAuthorizationUrl());
            final SupportedVersions supportedVersions =
                discoveryResponse.getProviderMetadata().getMobileConnectVersionSupported();
            authnOptionsBuilder.withClientName(discoveryResponse.getApplicationShortName());

            final StartAuthenticationResponse startAuthenticationResponse =
                authnService.startAuthentication(clientId, authorizationUrl,
                    config.getRedirectUrl(), state, nonce, encryptedMsisdn, supportedVersions,
                    authnOptionsBuilder.build());

            LOGGER.debug(
                "Responding with responseType={} for startAuthentication for encryptedMsisdn={}, state={}, nonce={}, startAuthenticationResponseUrl={}",
                MobileConnectStatus.ResponseType.AUTHENTICATION,
                LogUtils.mask(encryptedMsisdn, LOGGER, Level.DEBUG), state,
                LogUtils.mask(nonce, LOGGER, Level.DEBUG),
                LogUtils.maskUri(startAuthenticationResponse.getUrl(), LOGGER, Level.DEBUG));

            return MobileConnectStatus.authentication(
                startAuthenticationResponse.getUrl().toString(), state, nonce);
        }
        catch (final Exception e)
        {
            LOGGER.warn("startAuthentication failed for encryptedMsisdn={}, state={}, nonce={}",
                LogUtils.mask(encryptedMsisdn, LOGGER, Level.WARN), state,
                LogUtils.mask(nonce, LOGGER, Level.WARN), e);
            return MobileConnectStatus.error("start authentication", e);
        }
    }

    static MobileConnectStatus requestToken(final IAuthenticationService authnService,
        final DiscoveryResponse discoveryResponse, final URI redirectedUrl,
        final String expectedState, final String expectedNonce, final MobileConnectConfig config)
    {
        ObjectUtils.requireNonNull(discoveryResponse, "discoveryResponse");
        StringUtils.requireNonEmpty(expectedState, "expectedState");

        final String actualState = HttpUtils.extractQueryValue(redirectedUrl, "state");
        if (!expectedState.equals(actualState))
        {
            LOGGER.warn(
                "Responding with responseType={} for requestToken for redirectedUrl={}, expectedState={}, expectedNonce={}, as actualState={}; possible cross-site forgery",
                MobileConnectStatus.ResponseType.ERROR,
                LogUtils.maskUri(redirectedUrl, LOGGER, Level.WARN), expectedState,
                LogUtils.mask(expectedNonce, LOGGER, Level.WARN), actualState);

            return MobileConnectStatus.error("invalid_state",
                "state values do not match, possible cross-site request forgery", null);
        }
        else
        {
            final String code = HttpUtils.extractQueryValue(redirectedUrl, "code");
            final String clientId = ObjectUtils.defaultIfNull(
                discoveryResponse.getResponseData().getResponse().getClientId(),
                config.getClientId());
            final String clientSecret = ObjectUtils.defaultIfNull(
                discoveryResponse.getResponseData().getResponse().getClientSecret(),
                config.getClientSecret());
            final String requestTokenUrl = discoveryResponse.getOperatorUrls().getRequestTokenUrl();

            try
            {
                final RequestTokenResponse requestTokenResponse =
                    authnService.requestToken(clientId, clientSecret, URI.create(requestTokenUrl),
                        config.getRedirectUrl(), code);

                final ErrorResponse errorResponse = requestTokenResponse.getErrorResponse();
                if (errorResponse != null)
                {
                    LOGGER.warn(
                        "Responding with responseType={} for requestToken for redirectedUrl={}, expectedState={}, expectedNonce={}, authentication service responded with error={}",
                        MobileConnectStatus.ResponseType.ERROR, redirectedUrl, expectedState,
                        LogUtils.mask(expectedNonce, LOGGER, Level.WARN), errorResponse);

                    return MobileConnectStatus.error(errorResponse.getError(),
                        errorResponse.getErrorDescription(), null, requestTokenResponse);
                }
                else if (isExpectedNonce(requestTokenResponse.getResponseData().getIdToken(),
                    expectedNonce))
                {
                    LOGGER.warn(
                        "Responding with responseType={} for requestToken for redirectedUrl={}, expectedState={}, expectedNonce={}, as jwtToken did not contain expectedNonce; possible replay attack",
                        MobileConnectStatus.ResponseType.ERROR,
                        LogUtils.maskUri(redirectedUrl, LOGGER, Level.WARN), expectedState,
                        LogUtils.mask(expectedNonce, LOGGER, Level.WARN));

                    return MobileConnectStatus.error("invalid_nonce",
                        "nonce values do not match, possible replay attack", null);
                }
                else
                {
                    LOGGER.debug(
                        "Responding with responseType={} for requestToken for redirectedUrl={}, expectedState={}, expectedNonce={}",
                        MobileConnectStatus.ResponseType.COMPLETE,
                        LogUtils.maskUri(redirectedUrl, LOGGER, Level.DEBUG), expectedState,
                        LogUtils.mask(expectedNonce, LOGGER, Level.DEBUG));
                    return MobileConnectStatus.complete(requestTokenResponse);
                }
            }
            catch (final Exception e)
            {
                LOGGER.warn(
                    "requestToken failed for redirectedUrl={}, expectedState={}, expectedNonce={}",
                    LogUtils.maskUri(redirectedUrl, LOGGER, Level.WARN), expectedState,
                    LogUtils.mask(expectedNonce, LOGGER, Level.WARN), e);

                return MobileConnectStatus.error("request token", e);
            }
        }
    }

    private static boolean isExpectedNonce(final String token, final String expectedNonce)
    {
        final String decodedPayload = JsonWebTokens.Part.PAYLOAD.decode(token);
        final Matcher matcher = NONCE_REGEX.matcher(decodedPayload);

        return matcher.find() && matcher.group(1).equals(expectedNonce);
    }

    static MobileConnectStatus handleUrlRedirect(final IDiscoveryService discoveryService,
        final IAuthenticationService authnService, final URI redirectedUrl,
        final DiscoveryResponse discoveryResponse, final String expectedState,
        final String expectedNonce, final MobileConnectConfig config)
    {
        ObjectUtils.requireNonNull(redirectedUrl, "redirectedUrl");

        if (HttpUtils.extractQueryValue(redirectedUrl, Parameters.CODE) != null)
        {
            LOGGER.debug(
                "handleUrlRedirect redirecting to requestToken for redirectedUrl={}, expectedState={}, expectedNonce={}",
                LogUtils.maskUri(redirectedUrl, LOGGER, Level.DEBUG), expectedState,
                LogUtils.mask(expectedNonce, LOGGER, Level.DEBUG));

            return requestToken(authnService, discoveryResponse, redirectedUrl, expectedState,
                expectedNonce, config);
        }
        else if (HttpUtils.extractQueryValue(redirectedUrl, Parameters.MCC_MNC) != null)
        {
            LOGGER.debug(
                "handleUrlRedirect redirecting to attemptDiscoveryAfterOperatorSelection for redirectedUrl={}, expectedState={}, expectedNonce={}",
                LogUtils.maskUri(redirectedUrl, LOGGER, Level.DEBUG), expectedState,
                LogUtils.mask(expectedNonce, LOGGER, Level.DEBUG));

            return attemptDiscoveryAfterOperatorSelection(discoveryService, redirectedUrl, config);
        }
        else
        {
            final String errorCode = HttpUtils.extractQueryValue(redirectedUrl, Parameters.ERROR);
            final String errorDescription =
                HttpUtils.extractQueryValue(redirectedUrl, Parameters.ERROR_DESCRIPTION);

            final MobileConnectStatus status =
                MobileConnectStatus.error(ObjectUtils.defaultIfNull(errorCode, "invalid_request"),
                    ObjectUtils.defaultIfNull(errorDescription,
                        String.format("unable to parse next step using %s", redirectedUrl)), null);

            LOGGER.warn(
                "Responding with responseType={} for handleUrlRedirect for redirectedUrl={}, expectedState={}, expectedNonce={}; with error={}, description={}",
                MobileConnectStatus.ResponseType.ERROR,
                LogUtils.maskUri(redirectedUrl, LOGGER, Level.DEBUG), expectedState,
                LogUtils.mask(expectedNonce, LOGGER, Level.WARN), status.getErrorCode(),
                status.getErrorMessage());

            return status;
        }
    }

    static MobileConnectStatus requestUserInfo(final IIdentityService identityService,
        final DiscoveryResponse discoveryResponse, final String accessToken)
    {
        return requestInfo(identityService, accessToken,
            discoveryResponse.getOperatorUrls().getUserInfoUrl(), "requestUserInfo",
            MobileConnectStatus.ResponseType.USER_INFO);
    }

    static MobileConnectStatus requestIdentity(final IIdentityService identityService,
        final DiscoveryResponse discoveryResponse, final String accessToken)
    {
        return requestInfo(identityService, accessToken,
            discoveryResponse.getOperatorUrls().getPremiumInfoUri(), "requestIdentity",
            MobileConnectStatus.ResponseType.IDENTITY);
    }

    private static MobileConnectStatus requestInfo(final IIdentityService identityService,
        final String accessToken, final String infoUrl, final String method,
        final MobileConnectStatus.ResponseType responseType)
    {
        if (StringUtils.isNullOrEmpty(infoUrl))
        {
            LOGGER.warn(
                "Responding with responseType={} for {} for accessToken={}, provider does not support {}",
                MobileConnectStatus.ResponseType.ERROR, method,
                LogUtils.mask(accessToken, LOGGER, Level.WARN), responseType);

            return MobileConnectStatus.error("not_supported",
                String.format("%s not supported by current operator", responseType), null);
        }
        else
        {
            try
            {
                final IdentityResponse response =
                    identityService.requestInfo(URI.create(infoUrl), accessToken);

                final ErrorResponse errorResponse = response.getErrorResponse();
                if (errorResponse != null)
                {
                    LOGGER.warn(
                        "Responding with responseType={} for {} for accessToken={}, identity service responded with error={}",
                        MobileConnectStatus.ResponseType.ERROR, method,
                        LogUtils.mask(accessToken, LOGGER, Level.WARN), errorResponse);
                    return MobileConnectStatus.error(errorResponse.getError(),
                        errorResponse.getErrorDescription(), null);
                }
                else
                {
                    LOGGER.debug("Responding with responseType={} for {} for accessToken={}",
                        MobileConnectStatus.ResponseType.USER_INFO, method,
                        LogUtils.mask(accessToken, LOGGER, Level.DEBUG));

                    return new MobileConnectStatus.Builder()
                        .withResponseType(responseType)
                        .withIdentityResponse(response)
                        .build();
                }
            }
            catch (final Exception e)
            {
                LOGGER.warn("{} failed for accessToken={}", method,
                    LogUtils.mask(accessToken, LOGGER, Level.WARN), e);

                if (e instanceof IHasMobileConnectStatus)
                {
                    return ((IHasMobileConnectStatus) e).toMobileConnectStatus(
                        String.format("request %s", responseType));
                }
                else
                {
                    return MobileConnectStatus.error(String.format("request %s", responseType), e);
                }
            }
        }
    }

    private static MobileConnectStatus extractStatus(final DiscoveryResponse response,
        final IDiscoveryService service, final String task)
    {
        if (!response.isCached() && response.getErrorResponse() != null)
        {
            LOGGER.info("Responding with responseType={} for {}; errorResponse={}",
                MobileConnectStatus.ResponseType.ERROR, task, response.getErrorResponse());

            return MobileConnectStatus.error(response.getErrorResponse().getError(),
                ObjectUtils.defaultIfNull(response.getErrorResponse().getErrorDescription(),
                    "failure reported by discovery service, see response for more information"),
                null, response);
        }
        else
        {
            final String operatorSelectionUrl = service.extractOperatorSelectionURL(response);
            if (!StringUtils.isNullOrEmpty(operatorSelectionUrl))
            {
                LOGGER.debug("Responding with responseType={} for {}; operatorSelectionUrl={}",
                    MobileConnectStatus.ResponseType.OPERATOR_SELECTION, task,
                    LogUtils.maskUri(operatorSelectionUrl, LOGGER, Level.DEBUG));

                return MobileConnectStatus.operatorSelection(operatorSelectionUrl);
            }
            else
            {
                LOGGER.debug("Responding with responseType={} for {}",
                    MobileConnectStatus.ResponseType.START_AUTHENTICATION, task);

                return MobileConnectStatus.startAuthentication(response);
            }
        }
    }
}