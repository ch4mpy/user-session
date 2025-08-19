package com.c4_soft.ui;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.util.ThrowableAnalyzer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RestfulOAuth2Filter extends OAuth2AuthorizationRequestRedirectFilter {

  private final ThrowableAnalyzer throwableAnalyzer;

  private final RedirectStrategy authorizationRedirectStrategy;

  private final RedirectStrategy unauthorizedStrategy =
      (HttpServletRequest request, HttpServletResponse response, String location) -> {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.LOCATION, location);
      };

  private final OAuth2AuthorizationRequestResolver authorizationRequestResolver;

  private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository;

  private final AuthenticationFailureHandler authenticationFailureHandler;

  /**
   * Constructs an {@code OAuth2AuthorizationRequestRedirectFilter} using the provided parameters.
   * 
   * @param authorizationRequestResolver the resolver used for resolving authorization requests
   */
  public RestfulOAuth2Filter(OAuth2AuthorizationRequestResolver authorizationRequestResolver,
      AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository,
      RedirectStrategy authorizationRedirectStrategy,
      AuthenticationFailureHandler authenticationFailureHandler,
      ThrowableAnalyzer throwableAnalyzer) {
    super(authorizationRequestResolver);
    this.authorizationRequestResolver = authorizationRequestResolver;

    this.throwableAnalyzer = throwableAnalyzer;

    this.authorizationRedirectStrategy = authorizationRedirectStrategy;
    super.setAuthorizationRedirectStrategy(authorizationRedirectStrategy);


    this.authorizationRequestRepository = authorizationRequestRepository;
    super.setAuthorizationRequestRepository(authorizationRequestRepository);

    this.authenticationFailureHandler = authenticationFailureHandler;
    super.setAuthenticationFailureHandler(authenticationFailureHandler);
  }

  public RestfulOAuth2Filter(OAuth2AuthorizationRequestRedirectFilter other) {
    this(getField("authorizationRequestResolver", other),
        getField("authorizationRequestRepository", other),
        getField("authorizationRedirectStrategy", other),
        getField("authenticationFailureHandler", other), getField("throwableAnalyzer", other));
  }

  @SuppressWarnings("unchecked")
  private static <T> T getField(String fieldName, Object target) {
    try {
      final var field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return (T) field.get(target);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    try {
      OAuth2AuthorizationRequest authorizationRequest =
          this.authorizationRequestResolver.resolve(request);
      if (authorizationRequest != null) {
        this.sendRedirectForAuthorization(request, response, authorizationRequest);
        return;
      }
    } catch (Exception ex) {
      AuthenticationException wrappedException = new OAuth2AuthorizationRequestException(ex);
      this.authenticationFailureHandler.onAuthenticationFailure(request, response,
          wrappedException);
      return;
    }
    try {
      filterChain.doFilter(request, response);
    } catch (IOException ex) {
      throw ex;
    } catch (Exception ex) {
      // Check to see if we need to handle ClientAuthorizationRequiredException
      Throwable[] causeChain = this.throwableAnalyzer.determineCauseChain(ex);
      ClientAuthorizationRequiredException authzEx =
          (ClientAuthorizationRequiredException) this.throwableAnalyzer
              .getFirstThrowableOfType(ClientAuthorizationRequiredException.class, causeChain);
      if (authzEx != null) {
        try {
          OAuth2AuthorizationRequest authorizationRequest =
              this.authorizationRequestResolver.resolve(request, authzEx.getClientRegistrationId());
          if (authorizationRequest == null) {
            throw authzEx;
          }
          this.sendUnauthorized(request, response, authorizationRequest);
        } catch (Exception failed) {
          AuthenticationException wrappedException = new OAuth2AuthorizationRequestException(ex);
          this.authenticationFailureHandler.onAuthenticationFailure(request, response,
              wrappedException);
        }
        return;
      }
      if (ex instanceof ServletException) {
        throw (ServletException) ex;
      }
      if (ex instanceof RuntimeException) {
        throw (RuntimeException) ex;
      }
      throw new RuntimeException(ex);
    }
  }

  private void sendUnauthorized(HttpServletRequest request, HttpServletResponse response,
      OAuth2AuthorizationRequest authorizationRequest) throws IOException {
    this.unauthorizedStrategy.sendRedirect(request, response,
        authorizationRequest.getAuthorizationRequestUri());
  }

  private void sendRedirectForAuthorization(HttpServletRequest request,
      HttpServletResponse response, OAuth2AuthorizationRequest authorizationRequest)
      throws IOException {
    if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(authorizationRequest.getGrantType())) {
      this.authorizationRequestRepository.saveAuthorizationRequest(authorizationRequest, request,
          response);
    }
    this.authorizationRedirectStrategy.sendRedirect(request, response,
        authorizationRequest.getAuthorizationRequestUri());
  }

  private static final class OAuth2AuthorizationRequestException extends AuthenticationException {

    OAuth2AuthorizationRequestException(Throwable cause) {
      super(cause.getMessage(), cause);
    }

  }

}
