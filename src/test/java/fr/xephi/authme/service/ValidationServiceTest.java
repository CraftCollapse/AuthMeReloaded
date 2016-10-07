package fr.xephi.authme.service;

import ch.jalu.injector.testing.BeforeInjecting;
import ch.jalu.injector.testing.DelayedInjectionRunner;
import ch.jalu.injector.testing.InjectDelayed;
import com.google.common.base.Strings;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.permission.PermissionsManager;
import fr.xephi.authme.permission.PlayerStatePermission;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.EmailSettings;
import fr.xephi.authme.settings.properties.ProtectionSettings;
import fr.xephi.authme.settings.properties.RestrictionSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import fr.xephi.authme.service.ValidationService.ValidationResult;
import org.bukkit.command.CommandSender;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Collections;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Test for {@link ValidationService}.
 */
@RunWith(DelayedInjectionRunner.class)
public class ValidationServiceTest {

    @InjectDelayed
    private ValidationService validationService;
    @Mock
    private Settings settings;
    @Mock
    private DataSource dataSource;
    @Mock
    private PermissionsManager permissionsManager;
    @Mock
    private GeoIpService geoIpService;

    @BeforeInjecting
    public void createService() {
        given(settings.getProperty(RestrictionSettings.ALLOWED_PASSWORD_REGEX)).willReturn("[a-zA-Z]+");
        given(settings.getProperty(SecuritySettings.MIN_PASSWORD_LENGTH)).willReturn(3);
        given(settings.getProperty(SecuritySettings.MAX_PASSWORD_LENGTH)).willReturn(20);
        given(settings.getProperty(SecuritySettings.UNSAFE_PASSWORDS))
            .willReturn(asList("unsafe", "other-unsafe"));
        given(settings.getProperty(EmailSettings.MAX_REG_PER_EMAIL)).willReturn(3);
        given(settings.getProperty(RestrictionSettings.UNRESTRICTED_NAMES)).willReturn(asList("name01", "npc"));
    }

    @Test
    public void shouldRejectPasswordSameAsUsername() {
        // given/when
        ValidationResult error = validationService.validatePassword("bobby", "Bobby");

        // then
        assertErrorEquals(error, MessageKey.PASSWORD_IS_USERNAME_ERROR);
    }

    @Test
    public void shouldRejectPasswordNotMatchingPattern() {
        // given/when
        // service mock returns pattern a-zA-Z -> numbers should not be accepted
        ValidationResult error = validationService.validatePassword("invalid1234", "myPlayer");

        // then
        assertErrorEquals(error, MessageKey.PASSWORD_CHARACTERS_ERROR, "[a-zA-Z]+");
    }

    @Test
    public void shouldRejectTooShortPassword() {
        // given/when
        ValidationResult error = validationService.validatePassword("ab", "tester");

        // then
        assertErrorEquals(error, MessageKey.INVALID_PASSWORD_LENGTH);
    }

    @Test
    public void shouldRejectTooLongPassword() {
        // given/when
        ValidationResult error = validationService.validatePassword(Strings.repeat("a", 30), "player");

        // then
        assertErrorEquals(error, MessageKey.INVALID_PASSWORD_LENGTH);
    }

    @Test
    public void shouldRejectUnsafePassword() {
        // given/when
        ValidationResult error = validationService.validatePassword("unsafe", "playertest");

        // then
        assertErrorEquals(error, MessageKey.PASSWORD_UNSAFE_ERROR);
    }

    @Test
    public void shouldAcceptValidPassword() {
        // given/when
        ValidationResult error = validationService.validatePassword("safePass", "some_user");

        // then
        assertThat(error.hasError(), equalTo(false));
    }

    @Test
    public void shouldAcceptEmailWithEmptyLists() {
        // given
        given(settings.getProperty(EmailSettings.DOMAIN_WHITELIST)).willReturn(Collections.<String>emptyList());
        given(settings.getProperty(EmailSettings.DOMAIN_BLACKLIST)).willReturn(Collections.<String>emptyList());

        // when
        boolean result = validationService.validateEmail("test@example.org");

        // then
        assertThat(result, equalTo(true));
    }

    @Test
    public void shouldAcceptEmailWithWhitelist() {
        // given
        given(settings.getProperty(EmailSettings.DOMAIN_WHITELIST))
            .willReturn(asList("domain.tld", "example.com"));
        given(settings.getProperty(EmailSettings.DOMAIN_BLACKLIST)).willReturn(Collections.<String>emptyList());

        // when
        boolean result = validationService.validateEmail("TesT@Example.com");

        // then
        assertThat(result, equalTo(true));
    }

    @Test
    public void shouldRejectEmailNotInWhitelist() {
        // given
        given(settings.getProperty(EmailSettings.DOMAIN_WHITELIST))
            .willReturn(asList("domain.tld", "example.com"));
        given(settings.getProperty(EmailSettings.DOMAIN_BLACKLIST)).willReturn(Collections.<String>emptyList());

        // when
        boolean result = validationService.validateEmail("email@other-domain.abc");

        // then
        assertThat(result, equalTo(false));
    }

    @Test
    public void shouldAcceptEmailNotInBlacklist() {
        // given
        given(settings.getProperty(EmailSettings.DOMAIN_WHITELIST)).willReturn(Collections.<String>emptyList());
        given(settings.getProperty(EmailSettings.DOMAIN_BLACKLIST))
            .willReturn(asList("Example.org", "a-test-name.tld"));

        // when
        boolean result = validationService.validateEmail("sample@valid-name.tld");

        // then
        assertThat(result, equalTo(true));
    }

    @Test
    public void shouldRejectEmailInBlacklist() {
        // given
        given(settings.getProperty(EmailSettings.DOMAIN_WHITELIST)).willReturn(Collections.<String>emptyList());
        given(settings.getProperty(EmailSettings.DOMAIN_BLACKLIST))
            .willReturn(asList("Example.org", "a-test-name.tld"));

        // when
        boolean result = validationService.validateEmail("sample@a-Test-name.tld");

        // then
        assertThat(result, equalTo(false));
    }

    @Test
    public void shouldRejectInvalidEmail() {
        // given/when/then
        assertThat(validationService.validateEmail("invalidinput"), equalTo(false));
    }

    @Test
    public void shouldRejectDefaultEmail() {
        // given/when/then
        assertThat(validationService.validateEmail("your@email.com"), equalTo(false));
    }

    @Test
    public void shouldAllowRegistration() {
        // given
        CommandSender sender = mock(CommandSender.class);
        String email = "my.address@example.org";
        given(permissionsManager.hasPermission(sender, PlayerStatePermission.ALLOW_MULTIPLE_ACCOUNTS))
            .willReturn(false);
        given(dataSource.countAuthsByEmail(email)).willReturn(2);

        // when
        boolean result = validationService.isEmailFreeForRegistration(email, sender);

        // then
        assertThat(result, equalTo(true));
    }

    @Test
    public void shouldRejectEmailWithTooManyAccounts() {
        // given
        CommandSender sender = mock(CommandSender.class);
        String email = "mail@example.org";
        given(permissionsManager.hasPermission(sender, PlayerStatePermission.ALLOW_MULTIPLE_ACCOUNTS))
            .willReturn(false);
        given(dataSource.countAuthsByEmail(email)).willReturn(5);

        // when
        boolean result = validationService.isEmailFreeForRegistration(email, sender);

        // then
        assertThat(result, equalTo(false));
    }

    @Test
    public void shouldAllowBypassForPresentPermission() {
        // given
        CommandSender sender = mock(CommandSender.class);
        String email = "mail-address@example.com";
        given(permissionsManager.hasPermission(sender, PlayerStatePermission.ALLOW_MULTIPLE_ACCOUNTS))
            .willReturn(true);
        given(dataSource.countAuthsByEmail(email)).willReturn(7);

        // when
        boolean result = validationService.isEmailFreeForRegistration(email, sender);

        // then
        assertThat(result, equalTo(true));
    }

    @Test
    public void shouldRecognizeUnrestrictedNames() {
        assertThat(validationService.isUnrestricted("npc"), equalTo(true));
        assertThat(validationService.isUnrestricted("someplayer"), equalTo(false));
        assertThat(validationService.isUnrestricted("NAME01"), equalTo(true));

        // Check reloading
        given(settings.getProperty(RestrictionSettings.UNRESTRICTED_NAMES)).willReturn(asList("new", "names"));
        validationService.reload();
        assertThat(validationService.isUnrestricted("npc"), equalTo(false));
        assertThat(validationService.isUnrestricted("New"), equalTo(true));
    }

    @Test
    public void shouldNotInvokeGeoLiteApiIfCountryListsAreEmpty() {
        // given
        given(settings.getProperty(ProtectionSettings.COUNTRIES_WHITELIST)).willReturn(Collections.<String>emptyList());
        given(settings.getProperty(ProtectionSettings.COUNTRIES_BLACKLIST)).willReturn(Collections.<String>emptyList());

        // when
        boolean result = validationService.isCountryAdmitted("addr");

        // then
        assertThat(result, equalTo(true));
        verifyZeroInteractions(geoIpService);
    }

    @Test
    public void shouldAcceptCountryInWhitelist() {
        // given
        given(settings.getProperty(ProtectionSettings.COUNTRIES_WHITELIST)).willReturn(asList("ch", "it"));
        given(settings.getProperty(ProtectionSettings.COUNTRIES_BLACKLIST)).willReturn(Collections.<String>emptyList());
        String ip = "127.0.0.1";
        given(geoIpService.getCountryCode(ip)).willReturn("CH");

        // when
        boolean result = validationService.isCountryAdmitted(ip);

        // then
        assertThat(result, equalTo(true));
        verify(geoIpService).getCountryCode(ip);
    }

    @Test
    public void shouldRejectCountryMissingFromWhitelist() {
        // given
        given(settings.getProperty(ProtectionSettings.COUNTRIES_WHITELIST)).willReturn(asList("ch", "it"));
        given(settings.getProperty(ProtectionSettings.COUNTRIES_BLACKLIST)).willReturn(Collections.<String>emptyList());
        String ip = "123.45.67.89";
        given(geoIpService.getCountryCode(ip)).willReturn("BR");

        // when
        boolean result = validationService.isCountryAdmitted(ip);

        // then
        assertThat(result, equalTo(false));
        verify(geoIpService).getCountryCode(ip);
    }

    @Test
    public void shouldAcceptCountryAbsentFromBlacklist() {
        // given
        given(settings.getProperty(ProtectionSettings.COUNTRIES_WHITELIST)).willReturn(Collections.<String>emptyList());
        given(settings.getProperty(ProtectionSettings.COUNTRIES_BLACKLIST)).willReturn(asList("ch", "it"));
        String ip = "127.0.0.1";
        given(geoIpService.getCountryCode(ip)).willReturn("BR");

        // when
        boolean result = validationService.isCountryAdmitted(ip);

        // then
        assertThat(result, equalTo(true));
        verify(geoIpService).getCountryCode(ip);
    }

    @Test
    public void shouldRejectCountryInBlacklist() {
        // given
        given(settings.getProperty(ProtectionSettings.COUNTRIES_WHITELIST)).willReturn(Collections.<String>emptyList());
        given(settings.getProperty(ProtectionSettings.COUNTRIES_BLACKLIST)).willReturn(asList("ch", "it"));
        String ip = "123.45.67.89";
        given(geoIpService.getCountryCode(ip)).willReturn("IT");

        // when
        boolean result = validationService.isCountryAdmitted(ip);

        // then
        assertThat(result, equalTo(false));
        verify(geoIpService).getCountryCode(ip);
    }

    private static void assertErrorEquals(ValidationResult validationResult, MessageKey messageKey, String... args) {
        assertThat(validationResult.hasError(), equalTo(true));
        assertThat(validationResult.getMessageKey(), equalTo(messageKey));
        assertThat(validationResult.getArgs(), equalTo(args));
    }
}
