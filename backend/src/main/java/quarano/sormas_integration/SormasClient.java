package quarano.sormas_integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import quarano.sormas_integration.indexcase.SormasCase;
import quarano.sormas_integration.person.SormasContact;
import quarano.sormas_integration.person.SormasPerson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import org.springframework.util.Base64Utils;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Federico Grasso
 */
@Slf4j
@EnableAsync
@ConstructorBinding
@RequiredArgsConstructor
@Component
public class SormasClient {

    private WebClient client;

    private static final ObjectMapper mapper = new ObjectMapper();

    private String sormasUrl;
    private String sormasUser;
    private String sormasPass;

    public SormasClient(String sormasUrl, String sormasUser, String sormasPass) {

        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(MapperFeature.DEFAULT_VIEW_INCLUSION, true);

        this.sormasUrl = sormasUrl;
        this.sormasUser = sormasUser;
        this.sormasPass = sormasPass;

        client = WebClient
                .create(this.sormasUrl);
    }

    private WebClient.ResponseSpec GetRequest(String route){
        return client.get()
                .uri(route)
                .header("Authorization", "Basic " + Base64Utils
                        .encodeToString((sormasUser + ":" + sormasPass).getBytes(StandardCharsets.UTF_8)))
                .retrieve();
    }

    private WebClient.ResponseSpec PostRequest(String route, Object bodyRequest){
        return client.post()
                .uri(route)
                .header("Authorization", "Basic " + Base64Utils
                        .encodeToString((sormasUser + ":" + sormasPass).getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(Mono.just(bodyRequest), Object.class)
                .retrieve();
    }

    public Flux<SormasCase> getCases(LocalDateTime since){
        log.debug("Getting cases since " + since);
        return GetRequest("/cases/all/" + since.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .bodyToFlux(SormasCase.class);
    }

    public Flux<SormasCase> getCasesById(List<String> uuids){
        log.debug("Getting cases by Id...");
        return PostRequest("/cases/query", uuids)
                .bodyToFlux(SormasCase.class);
    }

    public Flux<SormasPerson> getPersons(LocalDateTime since) {
        log.debug("Getting persons since " + since);
        return GetRequest("/persons/all/" + since.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .bodyToFlux(SormasPerson.class);
    }

    public Mono<String[]> postPersons(List<SormasPerson> persons){
        log.debug("Starting person insert on SORMAS...");
        return PostRequest("/persons/push", persons)
                .bodyToMono(String[].class);
    }

    public Mono<String[]> postContacts(List<SormasContact> contacts){
        log.debug("Starting contact insert on SORMAS...");
        return PostRequest("/contacts/push", contacts)
                .bodyToMono(String[].class);
    }

    public Mono<String[]> postCases(List<SormasCase> cases){
        log.debug("Starting case insert on SORMAS...");
        return PostRequest("/cases/push", cases)
                .bodyToMono(String[].class);
    }
}
