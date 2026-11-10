package ru.cherdakmd.chrdkvk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * rc.9: цепочка PNG в беседу целиком проверяется на fake-транспорте, без
 * живого {@code api.vk.com}, пользовательского ключа и записи на стену.
 */
final class VkChatPhotoPublisherTest {

    private static final byte[] PNG_HEAD = {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10
    };
    private static final long PEER_ID = 2_000_000_001L;

    private static final class FakeTransport implements VkApiClient.Transport {
        final List<String> forms = new ArrayList<>();
        final List<URI> formUris = new ArrayList<>();
        final List<URI> uploadUris = new ArrayList<>();
        byte[] uploadedBody;
        String uploadUrl = "https://pu.vk.com/upload";
        String saveResponse = "{\"response\":[{\"owner_id\":-123,\"id\":77,"
                + "\"access_key\":\"a_b\"}]}";

        @Override
        public String postForm(URI uri, String formBody, Duration timeout) {
            forms.add(formBody);
            formUris.add(uri);
            String path = uri.getPath();
            if (path.endsWith("/photos.getMessagesUploadServer")) {
                return "{\"response\":{\"upload_url\":\"" + uploadUrl + "\"}}";
            }
            if (path.endsWith("/photos.saveMessagesPhoto")) {
                return saveResponse;
            }
            if (path.endsWith("/messages.send")) {
                return "{\"response\":42}";
            }
            throw new AssertionError("Неожиданный метод VK: " + path);
        }

        @Override
        public String get(URI uri, Duration timeout) {
            return "{}";
        }

        @Override
        public String postMultipart(URI uri, byte[] body, String contentType, Duration timeout) {
            uploadUris.add(uri);
            uploadedBody = body;
            return "{\"server\":1,\"photo\":\"ph\",\"hash\":\"hh\"}";
        }
    }

    private static VkApiClient api(FakeTransport fake) {
        return new VkApiClient("https://api.vk.com/method/", "COMMUNITY_TOKEN", "5.199", fake);
    }

    @Test
    void sendsPhotoToConversationOnCommunityTokenWithoutWallPost() throws Exception {
        FakeTransport fake = new FakeTransport();
        boolean sent = new VkChatPhotoPublisher(api(fake), 123L, PEER_ID)
                .send(PNG_HEAD, "Итоги дня", "chrdk-wall-2026-11-03");

        assertTrue(sent);
        assertEquals(3, fake.forms.size());
        assertTrue(fake.formUris.get(0).getPath().endsWith("/photos.getMessagesUploadServer"));
        assertTrue(fake.formUris.get(1).getPath().endsWith("/photos.saveMessagesPhoto"));
        assertTrue(fake.formUris.get(2).getPath().endsWith("/messages.send"));
        assertTrue(fake.forms.get(0).contains("peer_id=" + PEER_ID));
        assertTrue(fake.forms.get(1).contains("photo=ph"));
        assertTrue(fake.forms.get(1).contains("hash=hh"));
        assertTrue(fake.forms.get(1).contains("server=1"));

        String sendForm = URLDecoder.decode(fake.forms.get(2), StandardCharsets.UTF_8);
        assertTrue(sendForm.contains("group_id=123"));
        assertTrue(sendForm.contains("peer_id=" + PEER_ID));
        assertTrue(sendForm.contains("attachment=photo-123_77_a_b"));
        assertTrue(sendForm.contains("dont_parse_links=1"));
        assertTrue(sendForm.contains("message=Итоги дня"));
        assertTrue(sendForm.contains("random_id="));
        assertFalse(sendForm.contains("wall.post"));
        assertFalse(sendForm.contains("vk.me/join"), "подпись «обсудить» только для стены");
        assertFalse(sendForm.contains("access_token=USER_TOKEN"));
        assertTrue(fake.forms.get(2).contains("access_token=COMMUNITY_TOKEN"));

        assertEquals("https://pu.vk.com/upload", fake.uploadUris.get(0).toString());
        String multipart = new String(fake.uploadedBody, StandardCharsets.UTF_8);
        assertTrue(multipart.contains("name=\"photo\""));
        assertTrue(multipart.contains("Content-Type: image/png"));
    }

    @Test
    void requiresConversationPeerIdAndRealPngBeforeAnyRequest() {
        FakeTransport fake = new FakeTransport();
        VkApiClient api = api(fake);
        assertThrows(IOException.class, () -> new VkChatPhotoPublisher(api, 123L, 123L)
                .send(PNG_HEAD, "Итоги дня", "guid"));
        assertThrows(IOException.class, () -> new VkChatPhotoPublisher(api, 123L, PEER_ID)
                .send(new byte[]{1, 2, 3}, "Итоги дня", "guid"));
        assertTrue(fake.forms.isEmpty());
        assertTrue(fake.uploadUris.isEmpty());
    }

    @Test
    void foreignUploadUrlAbortsBeforeUploadingOrSending() {
        FakeTransport fake = new FakeTransport();
        fake.uploadUrl = "https://evil.example/upload";
        assertThrows(IOException.class, () -> new VkChatPhotoPublisher(api(fake), 123L, PEER_ID)
                .send(PNG_HEAD, "Итоги дня", "guid"));
        assertEquals(1, fake.forms.size(), "Вызван только photos.getMessagesUploadServer");
        assertTrue(fake.uploadUris.isEmpty(), "Файл никуда не отправлен");
    }

    @Test
    void missingSavedPhotoAbortsBeforeMessagesSend() {
        FakeTransport fake = new FakeTransport();
        fake.saveResponse = "{\"response\":[{\"owner_id\":0,\"id\":77}]}";
        assertThrows(IOException.class, () -> new VkChatPhotoPublisher(api(fake), 123L, PEER_ID)
                .send(PNG_HEAD, "Итоги дня", "guid"));
        assertEquals(2, fake.forms.size());
        assertFalse(fake.formUris.get(1).getPath().endsWith("/messages.send"));
    }

    @Test
    void stableRandomIdIsPositiveAndDeterministic() {
        String seed = "123:" + PEER_ID + ":chrdk-wall-2026-11-03";
        int id = VkChatPhotoPublisher.stableRandomId(seed);
        assertTrue(id > 0);
        assertEquals(id, VkChatPhotoPublisher.stableRandomId(seed));
        assertTrue(id != VkChatPhotoPublisher.stableRandomId(seed + "-other"));
    }
}
