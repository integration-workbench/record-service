package io.github.huck5.recordservice.contact.client;

import io.github.huck5.recordservice.CrmSource;
import io.github.huck5.recordservice.contact.dto.ContactRetrievalResponse;
import io.github.huck5.recordservice.contact.exception.ImportRequestFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactImportClientTest {
    @Mock private HttpClient http;
    @Mock private HttpResponse<String> response;
    private ContactImportClient client;

    @BeforeEach
    void setUp() {
        client = new ContactImportClient(http, JsonMapper.builder().build());
    }

    @ParameterizedTest
    @EnumSource(CrmSource.class)
    void fetchesOnePageFromSelectedSourceAndDeserializesEnvelope(CrmSource source) throws Exception {
        successfulResponse("""
                {"contacts":[{"source":"%s","externalId":"id-1","firstName":"Bob"}],
                 "totalAvailable":251,"hasMore":true,"continuationToken":"page-2"}
                """.formatted(source.name()));

        ContactRetrievalResponse page = client.fetchPage(source, null);

        assertEquals(1, page.contacts().size());
        assertEquals("id-1", page.contacts().getFirst().getExternalId());
        assertEquals(251L, page.totalAvailable());
        assertTrue(page.hasMore());
        assertEquals("page-2", page.continuationToken());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals("GET", request.getValue().method());
        assertEquals("http://localhost:8080/" + source.name().toLowerCase(Locale.ROOT) + "/contacts",
                request.getValue().uri().toString());
    }

    @Test
    void sendsContinuationTokenAsOneEncodedQueryParameter() throws Exception {
        successfulResponse("""
                {"contacts":[],"totalAvailable":null,"hasMore":false,"continuationToken":null}
                """);

        client.fetchPage(CrmSource.SALESFORCE, "/query/next?after=a+b&key=x=y");

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals("continuationToken=%2Fquery%2Fnext%3Fafter%3Da%2Bb%26key%3Dx%3Dy",
                request.getValue().uri().getRawQuery());
    }

    @Test
    void nonSuccessfulHttpStatusThrowsRetrievalFailure() throws Exception {
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        when(response.statusCode()).thenReturn(503);

        ImportRequestFailedException failure = assertThrows(ImportRequestFailedException.class,
                () -> client.fetchPage(CrmSource.HUBSPOT, null));

        assertTrue(failure.getMessage().contains("503"));
        verify(response, never()).body();
    }

    @ParameterizedTest
    @MethodSource("ioFailures")
    void networkFailurePreservesCause(IOException cause) throws Exception {
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(cause);

        ImportRequestFailedException failure = assertThrows(ImportRequestFailedException.class,
                () -> client.fetchPage(CrmSource.HUBSPOT, null));

        assertSame(cause, failure.getCause());
    }

    @Test
    void interruptionPreservesCauseAndRestoresInterruptFlag() throws Exception {
        InterruptedException cause = new InterruptedException("Request interrupted");
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(cause);
        try {
            ImportRequestFailedException failure = assertThrows(ImportRequestFailedException.class,
                    () -> client.fetchPage(CrmSource.HUBSPOT, null));
            assertSame(cause, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // Do not leak the interrupt flag into other tests.
        }
    }

    @Test
    void malformedResponseCannotBecomeSuccessfulPage() throws Exception {
        successfulResponse("[invalid json");

        assertThrows(JacksonException.class, () -> client.fetchPage(CrmSource.HUBSPOT, null));
    }

    private void successfulResponse(String body) throws Exception {
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
    }

    private static Stream<IOException> ioFailures() {
        return Stream.of(new IOException("Connection refused"), new HttpTimeoutException("Timed out"));
    }
}
