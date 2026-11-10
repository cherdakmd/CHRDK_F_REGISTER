package ru.cherdakmd.chrdkvk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Цепочка photos → wall.post целиком проверяется на fake-транспорте, без api.vk.com. */
class VkWallPublisherTest {

    private static final byte[] PNG_HEAD = {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10
    };

    private static final class FakeTransport implements VkApiClient.Transport {
        final List<String> forms = new ArrayList<>();
        final List<URI> formUris = new ArrayList<>();
        final List<URI> uploadUris = new ArrayList<>();
        byte[] uploadedBody;
        String uploadUrl = "https://pu.vk.com/upload";
        String wallPostResponse = "{\"response\":{\"post_id\":88}}";

        @Override
        public String postForm(URI uri, String formBody, Duration timeout) {
            forms.add(formBody);
            formUris.add(uri);
            String path = uri.getPath();
            if (path.endsWith("/photos.getWallUploadServer")) {
                return "{\"response\":{\"upload_url\":\"" + uploadUrl + "\"}}";
            }
            if (path.endsWith("/photos.saveWallPhoto")) {
                return "{\"response\":[{\"owner_id\":-123,\"id\":77}]}";
            }
            if (path.endsWith("/wall.post")) {
                return wallPostResponse;
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
        return api(fake, "token");
    }

    private static VkApiClient api(FakeTransport fake, String token) {
        return new VkApiClient("https://api.vk.com/method/", token, "5.199", fake);
    }

    @Test
    void publishesFromGroupWithPhotoAttachmentAndStableGuid() throws Exception {
        FakeTransport fake = new FakeTransport();
        long postId = new VkWallPublisher(api(fake), 123L)
                .publish(PNG_HEAD, "Итоги дня\n\nПрестиж: 3 ступени", "chrdk-wall-2026-11-03");

        assertEquals(88L, postId);
        assertEquals(3, fake.forms.size());
        assertTrue(fake.forms.get(0).contains("group_id=123"));
        assertTrue(fake.forms.get(1).contains("photo=ph"));
        assertTrue(fake.forms.get(1).contains("hash=hh"));
        assertTrue(fake.forms.get(1).contains("server=1"));
        assertTrue(fake.forms.get(2).contains("owner_id=-123"), "Публикуем на стену сообщества");
        assertTrue(fake.forms.get(2).contains("from_group=1"), "Пост от имени сообщества");
        assertTrue(fake.forms.get(2).contains("attachments=photo-123_77"));
        assertTrue(fake.forms.get(2).contains("guid=chrdk-wall-2026-11-03"));
        assertEquals("https://pu.vk.com/upload", fake.uploadUris.get(0).toString());
        String multipart = new String(fake.uploadedBody, StandardCharsets.UTF_8);
        assertTrue(multipart.contains("name=\"photo\""));
        assertTrue(multipart.contains("filename=\"chrdk-digest.png\""));
        assertTrue(multipart.contains("Content-Type: image/png"));
    }

    @Test
    void photoMethodsUseUserTokenAndWallPostUsesCommunityToken() throws Exception {
        FakeTransport community = new FakeTransport();
        FakeTransport user = new FakeTransport();
        VkApiClient communityApi = api(community, "COMMUNITY_TOKEN");
        VkApiClient photoApi = api(user, "USER_TOKEN");

        long postId = new VkWallPublisher(communityApi, photoApi, 123L)
                .publish(PNG_HEAD, "Итоги дня", "guid");

        assertEquals(88L, postId);
        assertEquals(2, user.forms.size(), "photos.* ушли на пользовательский ключ");
        assertEquals(1, community.forms.size(), "wall.post остался на ключе сообщества");
        assertTrue(user.formUris.get(0).getPath().endsWith("/photos.getWallUploadServer"));
        assertTrue(user.formUris.get(1).getPath().endsWith("/photos.saveWallPhoto"));
        assertTrue(community.formUris.get(0).getPath().endsWith("/wall.post"));
        for (String form : user.forms) {
            assertTrue(form.contains("access_token=USER_TOKEN"), form);
            assertFalse(form.contains("COMMUNITY_TOKEN"));
        }
        String wallPost = community.forms.get(0);
        assertTrue(wallPost.contains("access_token=COMMUNITY_TOKEN"));
        assertFalse(wallPost.contains("USER_TOKEN"));
        assertTrue(wallPost.contains("from_group=1"));
        assertTrue(wallPost.contains("attachments=photo-123_77"));
        assertEquals(1, user.uploadUris.size(), "multipart тоже идёт через фото-клиент");
        assertTrue(community.uploadUris.isEmpty());
    }

    @Test
    void nullPhotoClientKeepsLegacySingleTokenMode() throws Exception {
        FakeTransport fake = new FakeTransport();
        long postId = new VkWallPublisher(api(fake), null, 123L)
                .publish(PNG_HEAD, "Итоги дня", "guid");
        assertEquals(88L, postId);
        assertEquals(3, fake.forms.size());
    }

    @Test
    void publishesTextTestPostWithoutPhotoAndWithOwnGuid() throws Exception {
        FakeTransport fake = new FakeTransport();
        long postId = new VkWallPublisher(api(fake), 123L)
                .publishText("🧪 Тест CHRDK_VK\n" + VkWallPublisher.TEST_POST_MARKER + "abcd1234",
                        "chrdk-test-42");

        assertEquals(88L, postId);
        assertEquals(1, fake.forms.size(), "Только wall.post, без photos.getWallUploadServer");
        String form = fake.forms.get(0);
        assertTrue(form.contains("owner_id=-123"));
        assertTrue(form.contains("from_group=1"));
        assertTrue(form.contains("guid=chrdk-test-42"));
        assertTrue(form.contains("message="));
        assertFalse(form.contains("attachments"), "У тестовой записи нет вложения-постера");
        assertTrue(fake.uploadUris.isEmpty(), "Файл никуда не загружается");
    }

    @Test
    void textTestPostRejectsBlankMessageAndMissingGroup() {
        FakeTransport fake = new FakeTransport();
        assertThrows(IOException.class, () -> new VkWallPublisher(api(fake), 123L)
                .publishText("  ", "guid"));
        assertThrows(IOException.class, () -> new VkWallPublisher(api(fake), 0L)
                .publishText("🧪 Тест", "guid"));
        assertTrue(fake.forms.isEmpty(), "До API запросов не дошло");
    }

    @Test
    void foreignUploadUrlAbortsBeforeUploadingOrPosting() {
        FakeTransport fake = new FakeTransport();
        fake.uploadUrl = "https://evil.example/upload";
        assertThrows(IOException.class, () -> new VkWallPublisher(api(fake), 123L)
                .publish(PNG_HEAD, "Итоги дня", "guid"));
        assertEquals(1, fake.forms.size(), "Вызван только photos.getWallUploadServer");
        assertTrue(fake.uploadUris.isEmpty(), "Файл никуда не отправлен");
    }

    @Test
    void missingPostIdTreatedAsFailure() {
        FakeTransport fake = new FakeTransport();
        fake.wallPostResponse = "{\"response\":{}}";
        assertThrows(IOException.class, () -> new VkWallPublisher(api(fake), 123L)
                .publish(PNG_HEAD, "Итоги дня", "guid"));
    }

    @Test
    void uploadHostAllowlistRejectsNonVkDomains() throws Exception {
        assertThrows(IOException.class,
                () -> VkWallPublisher.validateUploadUrl("http://pu.vk.com/upload"));
        assertThrows(IOException.class,
                () -> VkWallPublisher.validateUploadUrl("https://evil.example/upload"));
        assertThrows(IOException.class,
                () -> VkWallPublisher.validateUploadUrl("https://user@pu.vk.com/upload"));
        assertEquals(URI.create("https://cs1.userapi.com/upload"),
                VkWallPublisher.validateUploadUrl("https://cs1.userapi.com/upload"));
    }
}
