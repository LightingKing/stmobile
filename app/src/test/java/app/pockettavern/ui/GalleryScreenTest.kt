package app.pockettavern.ui

import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryScreenTest {

    private fun chat(characterId: String, messages: List<Message>) =
        Chat(id = "chat-$characterId", characterId = characterId, messages = messages)

    private fun imageMsg(attachmentId: String, mime: String = "image/jpeg", dataUrl: String = "data:image/jpeg;base64,abc", at: Long = 0L) =
        Message(id = "msg-$attachmentId", createdAt = at, attachments = listOf(
            Attachment(id = attachmentId, name = "pic.jpg", mime = mime, dataUrl = dataUrl)
        ))

    @Test fun `collects image attachments from character chats`() {
        val chats = listOf(
            chat("c1", listOf(imageMsg("a1", at = 100), imageMsg("a2", at = 200))),
            chat("c2", listOf(imageMsg("a3")))   // different character
        )
        val items = collectGalleryItems(chats, "c1")
        assertEquals(2, items.size)
        assertTrue(items.all { it.chatId == "chat-c1" })
        // sorted descending by createdAt
        assertEquals("a2", items[0].attachmentId)
        assertEquals("a1", items[1].attachmentId)
    }

    @Test fun `ignores non-image attachments`() {
        val chats = listOf(chat("c1", listOf(
            Message(id = "m1", attachments = listOf(
                Attachment(id = "file1", name = "doc.pdf", mime = "application/pdf", dataUrl = "data:application/pdf;base64,xyz")
            )),
            imageMsg("img1")
        )))
        val items = collectGalleryItems(chats, "c1")
        assertEquals(1, items.size)
        assertEquals("img1", items[0].attachmentId)
    }

    @Test fun `ignores attachments with blank dataUrl`() {
        val chats = listOf(chat("c1", listOf(
            Message(id = "m1", attachments = listOf(
                Attachment(id = "empty1", name = "pic.jpg", mime = "image/jpeg", dataUrl = "")
            ))
        )))
        val items = collectGalleryItems(chats, "c1")
        assertTrue(items.isEmpty())
    }

    @Test fun `returns empty for character with no chats`() {
        val chats = listOf(chat("c2", listOf(imageMsg("a1"))))
        assertTrue(collectGalleryItems(chats, "c1").isEmpty())
    }

    @Test fun `countGalleryImages matches collected items`() {
        val chats = listOf(
            chat("c1", listOf(imageMsg("a1"), imageMsg("a2"))),
            chat("c1", listOf(imageMsg("a3")))
        )
        assertEquals(collectGalleryItems(chats, "c1").size, countGalleryImages(chats, "c1"))
    }
}
