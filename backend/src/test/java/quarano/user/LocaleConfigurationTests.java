package quarano.user;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpHeaders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import lombok.RequiredArgsConstructor;
import quarano.QuaranoWebIntegrationTest;
import quarano.WithQuaranoUser;
import quarano.core.I18nProperties;
import quarano.tracking.TrackedPerson;
import quarano.tracking.TrackedPersonDataInitializer;
import quarano.tracking.TrackedPersonRepository;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.NestedServletException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

@QuaranoWebIntegrationTest
@RequiredArgsConstructor
public class LocaleConfigurationTests {

	static final String ME = "/user/me";

	final String USERNAME_WITH_LOCALE = "DemoAccount";
	final String USERNAME_WITHOUT_LOCALE = "test3";

	final MockMvc mvc;
	final ObjectMapper mapper;
	final TrackedPersonRepository persons;
	final I18nProperties i18nProperties;

	@Test
	@WithQuaranoUser(USERNAME_WITHOUT_LOCALE)
	void testLocaleHandlingWithDefault() throws Exception {

		var responseGet = performGet(ME, null);
		var document = JsonPath.parse(responseGet.getContentAsString());

		assertThat(responseGet.getHeader(CONTENT_LANGUAGE)).isEqualTo(i18nProperties.getDefaultLocale().toString());
		assertThat(document.read("$.client.locale", String.class)).isNull();

		var responsePut = performWrongPasswordChange(Locale.KOREAN); // unsupported language
		document = JsonPath.parse(responsePut.getContentAsString());

		assertThat(responsePut.getHeader(CONTENT_LANGUAGE)).isEqualTo(i18nProperties.getDefaultLocale().toString());
		assertThat(document.read("$.current", String.class)).isEqualTo("Ung??ltiges aktuelles Passwort!");
	}

	@Test
	@WithQuaranoUser(USERNAME_WITHOUT_LOCALE)
	void testLocaleHandlingWithAcceptLanguageHeader() throws Exception {

		var responseGet = performGet(ME, Locale.ENGLISH);
		var document = JsonPath.parse(responseGet.getContentAsString());

		assertThat(responseGet.getHeader(CONTENT_LANGUAGE)).isEqualTo("en");
		assertThat(document.read("$.client.locale", String.class)).isNull();

		var responsePut = performWrongPasswordChange(Locale.ENGLISH);
		document = JsonPath.parse(responsePut.getContentAsString());

		assertThat(responsePut.getHeader(CONTENT_LANGUAGE)).isEqualTo("en");
		assertThat(document.read("$.current", String.class)).isEqualTo("Invalid current password!");
	}

	@Test
	@WithQuaranoUser(USERNAME_WITH_LOCALE)
	void testLocaleHandlingWithUserSetting() throws Exception {

		var locale = Locale.forLanguageTag("tr");

		var responseGet = performGet(ME, locale);
		var document = JsonPath.parse(responseGet.getContentAsString());

		assertThat(responseGet.getHeader(CONTENT_LANGUAGE)).isEqualTo("en");
		assertThat(document.read("$.client.locale", String.class)).isEqualTo("en");

		var responsePut = performWrongPasswordChange(locale);
		document = JsonPath.parse(responsePut.getContentAsString());

		assertThat(responsePut.getHeader(CONTENT_LANGUAGE)).isEqualTo("en");
		assertThat(document.read("$.current", String.class)).isEqualTo("Invalid current password!");
	}

	@WithQuaranoUser(USERNAME_WITHOUT_LOCALE)
	@ParameterizedTest
	@ValueSource(strings = { "de", "en", "tr" })
	void processCorrectLocalesSucceeds(String locale) throws Exception {

		mvc.perform(get(ME)
				.header("Origin", "*")
				.locale(Locale.forLanguageTag("tr"))
				.param("locale", locale))
				.andExpect(status().is2xxSuccessful());

		assertThat(persons.findById(TrackedPersonDataInitializer.VALID_TRACKED_PERSON3_ID_DEP2))
				.isPresent()
				.map(TrackedPerson::getLocale)
				.map(Locale::toString)
				.hasValue(locale);
	}

	@WithQuaranoUser(USERNAME_WITHOUT_LOCALE)
	@ParameterizedTest
	@ValueSource(strings = { "en,de", "en_US,tr_TR" })
	void processInvalidLocalesRejects(String locale) throws Exception {

		var exception = assertThrows(NestedServletException.class, () -> {
			mvc.perform(get(ME)
					.header("Origin", "*")
					.param("locale", locale)
					.contentType(MediaType.APPLICATION_JSON))
					.andExpect(status().is4xxClientError());
		});

		assertThat(exception.getCause()).isInstanceOf(IllegalArgumentException.class);
	}

	private MockHttpServletResponse performGet(String urlTemplate, Locale locale) throws Exception {

		return mvc.perform(get(urlTemplate)
				.header("Origin", "*")
				.locale(locale)
				.contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaTypes.HAL_JSON))
				.andReturn().getResponse();
	}

	private MockHttpServletResponse performWrongPasswordChange(Locale locale) throws Exception {

		return mvc.perform(put("/user/me/password")
				.header("Origin", "*")
				.locale(locale)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createRequestBody("xxxx#", "xxxx#", "xxxx#")))
				.andExpect(status().is4xxClientError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andReturn().getResponse();
	}

	private String createRequestBody(String current, String password, String passwordConfirm) throws Exception {
		return mapper
				.writeValueAsString(Map.of("current", current, "password", password, "passwordConfirm", passwordConfirm));
	}
}
