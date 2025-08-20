package com.c4_soft.users_api;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.c4_soft.springaddons.security.oauth2.test.annotations.WithJwt;
import com.c4_soft.springaddons.security.oauth2.test.webmvc.AutoConfigureAddonsWebmvcResourceServerSecurity;
import com.c4_soft.springaddons.security.oauth2.test.webmvc.MockMvcSupport;
import com.c4_soft.users_api.keycloak.AdminApiService;
import com.c4_soft.users_api.users.UsersController;

@WebMvcTest(UsersController.class)
@AutoConfigureAddonsWebmvcResourceServerSecurity
@Import({SecurityConfiguration.class}) // Import security configuration to enable method security
class UsersControllerTest {

  @Autowired
  MockMvcSupport api;

  @MockitoBean
  AdminApiService adminApi; // Mocked to avoid Keycloak connection issues in tests

  // ------ /
  // Get me /
  // ------ /

  @Test
  @WithAnonymousUser
  void givenRequestIsAnonymous_whenGetMe_thenUnauthorized() throws Exception {
    // @formatter:off
    api.get("https://localhost/users/me")
      .andExpect(status().isUnauthorized());
    // @formatter:on
  }

  @Test
  @WithJwt("bidule-chose.ui.json")
  void givenRequestIsAuthorized_whenGetMe_thenReturnValuesFromTokenClaims() throws Exception {
    // @formatter:off
    api.get("https://localhost/users/me")
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.sub").exists())
      .andExpect(jsonPath("$.sub", is("787e157c-2b81-4d6e-b6aa-939d1cfb2114")))
      .andExpect(jsonPath("$.preferredUsername", is("bidule@chose.pf")))
      .andExpect(jsonPath("$.email", is("bidule@chose.pf")))
      .andExpect(jsonPath("$.roles").isArray())
      .andExpect(jsonPath("$.roles.length()", is(1)))
      .andExpect(jsonPath("$.roles", hasItem("ui.trainee")));
    // @formatter:on
  }

}
