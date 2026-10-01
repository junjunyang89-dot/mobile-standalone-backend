package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginEndpointFinderTest {

    @Test
    void loginPathFilter() {
        assertThat(LoginEndpointFinder.isLoginPath("/account/login")).isTrue();
        assertThat(LoginEndpointFinder.isLoginPath("/mini/loginByPhone")).isTrue();
        assertThat(LoginEndpointFinder.isLoginPath("/account/logout")).isFalse();
        assertThat(LoginEndpointFinder.isLoginPath("/account/updateLoginTime")).isFalse();
        assertThat(LoginEndpointFinder.isLoginPath("/user/steamLoginCallback")).isFalse();
    }

    @Test
    void bodyTemplateMapsAccountAndSecret() {
        String body = LoginEndpointFinder.bodyTemplate(List.of("appId", "areaCode", "mobilePhone", "securityCode"), "2");
        assertThat(body.replaceAll("\\s+", ""))
                .isEqualTo("{\"appId\":2,\"areaCode\":\"\",\"mobilePhone\":\"{{loginAccount}}\",\"securityCode\":\"{{loginSecret}}\"}");
    }

    @Test
    void ranksClientCalledAccountLoginFirst(@TempDir Path root) throws Exception {
        write(root, "passport/.git/HEAD", "");
        write(root, "passport/src/main/java/p/AccountController.java", """
                @RestController
                @RequestMapping("/account")
                public class AccountController {
                    @PostMapping("/login")
                    public ResponseResult<Object> login(@RequestBody LoginParam p) { return null; }
                    @PostMapping("/qrLogin")
                    public ResponseResult<Object> qrLogin(@RequestBody QrParam p) { return null; }
                }
                """);
        write(root, "passport/src/main/java/p/LoginParam.java",
                "@Data public class LoginParam { private Integer appId; private String mobilePhone; private String securityCode; }");
        write(root, "passport/src/main/java/p/QrParam.java", "@Data public class QrParam { private String ticket; }");
        write(root, "android/app/LoginApi.kt", "@POST(\"{baseUrl}/account/login\") fun login(): Call<Any>\n");
        write(root, "android/app/PlatformConstants.kt", "const val APP_ID = 2\n");

        var list = LoginEndpointFinder.find(List.of(root));

        assertThat(list).isNotEmpty();
        var top = list.get(0);
        assertThat(top.path()).isEqualTo("/account/login");
        assertThat(top.automatable()).isTrue();
        assertThat(top.callerRepos()).containsExactly("android");
        assertThat(new ObjectMapper().readTree(top.bodyTemplate()).get("appId").asInt()).isEqualTo(2);
    }

    @Test
    void findsTokenInNestedLoginResult() throws Exception {
        var n = new ObjectMapper().readTree(
                "{\"code\":0,\"result\":{\"loginResult\":{\"userType\":1,\"accountInfo\":{\"userId\":9,\"token\":\"abcdef123456\"}}}}");
        assertThat(ApiAuthService.findTokenPath(n, "$")).isEqualTo("$.result.loginResult.accountInfo.token");
    }

    private static void write(Path root, String rel, String content) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }
}
