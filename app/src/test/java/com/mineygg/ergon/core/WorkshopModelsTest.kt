package com.mineygg.ergon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Machine Workshop / Traveling Mimo events: how each game is described, and what the shop and prize draw numbers mean. */
class WorkshopModelsTest {
    private fun award(status: Int, nextRefresh: Long = 0L) = ExchangeAward(1, status, "Reward", 100, 5, nextRefresh, 7)

    @Test
    fun `each workshop game belongs to a game and shares its id`() {
        assertEquals(Game.GENSHIN, WorkshopGame.GENSHIN.game)
        assertEquals(Game.HSR, WorkshopGame.HSR.game)
        assertEquals(Game.ZZZ, WorkshopGame.ZZZ.game)
        for (w in WorkshopGame.entries) assertEquals(w.game.id, w.id)
    }

    @Test
    fun `the event ids of HoYoLAB stay as captured`() {
        assertEquals(2, WorkshopGame.GENSHIN.hoyoGameId)
        assertEquals(6, WorkshopGame.HSR.hoyoGameId)
        assertEquals(8, WorkshopGame.ZZZ.hoyoGameId)
    }

    @Test
    fun `only Genshin uses the older API`() {
        assertTrue(WorkshopGame.GENSHIN.legacy)
        assertFalse(WorkshopGame.HSR.legacy)
        assertFalse(WorkshopGame.ZZZ.legacy)
    }

    @Test
    fun `labels name the event, and the game when the event is shared`() {
        assertEquals("Machine Workshop", WorkshopGame.GENSHIN.label)
        assertEquals("Traveling Mimo (Star Rail)", WorkshopGame.HSR.label)
        assertEquals("Traveling Mimo (Zenless)", WorkshopGame.ZZZ.label)
    }

    @Test
    fun `Genshin keeps the old history name, the others get their own`() {
        assertEquals("WORKSHOP", WorkshopGame.GENSHIN.recordTask)
        assertEquals("MIMO_HSR", WorkshopGame.HSR.recordTask)
        assertEquals("MIMO_ZZZ", WorkshopGame.ZZZ.recordTask)
        assertEquals(WorkshopGame.entries.size, WorkshopGame.entries.map { it.recordTask }.toSet().size)
    }

    @Test
    fun `only Traveling Mimo has a prize draw`() {
        assertFalse(WorkshopGame.GENSHIN.canDraw)
        assertTrue(WorkshopGame.HSR.canDraw)
        assertTrue(WorkshopGame.ZZZ.canDraw)
    }

    @Test
    fun `in-game task types are told apart from community ones`() {
        assertTrue(WorkshopGame.GENSHIN.isGameTask(6))
        assertFalse(WorkshopGame.GENSHIN.isGameTask(101))
        assertTrue(WorkshopGame.HSR.isGameTask(8))
        assertFalse(WorkshopGame.HSR.isGameTask(6))
        assertTrue(WorkshopGame.ZZZ.isGameTask(12))
        assertTrue(WorkshopGame.ZZZ.isGameTask(16))
        assertFalse(WorkshopGame.ZZZ.isGameTask(8))
    }

    @Test
    fun `tasks that need the player are only watched`() {
        for (id in listOf(4, 10, 34)) assertTrue(WorkshopGame.GENSHIN.needsPlayer(id))
        for (id in listOf(3, 12, 18)) assertTrue(WorkshopGame.HSR.needsPlayer(id))
        for (id in listOf(3, 4, 5, 6, 7, 9)) assertTrue(WorkshopGame.ZZZ.needsPlayer(id))
        assertFalse(WorkshopGame.GENSHIN.needsPlayer(1))
        assertFalse(WorkshopGame.HSR.needsPlayer(4))
        assertFalse(WorkshopGame.ZZZ.needsPlayer(12))
    }

    @Test
    fun `a task is claimed at status 3`() {
        assertEquals(3, WorkshopTask.STATUS_CLAIMED)
    }

    @Test
    fun `a reward on the shelf can be claimed at once`() {
        val a = award(ExchangeAward.STATUS_AVAILABLE)
        assertTrue(a.claimable)
        assertFalse(a.timed)
    }

    @Test
    fun `a reward on a refresh timer is timed and not claimable yet`() {
        val a = award(ExchangeAward.STATUS_AVAILABLE, nextRefresh = 1_900_000_000L)
        assertFalse(a.claimable)
        assertTrue(a.timed)
    }

    @Test
    fun `claimed, sold out and unknown statuses are never claimable`() {
        assertFalse(award(ExchangeAward.STATUS_CLAIMED).claimable)
        assertFalse(award(ExchangeAward.STATUS_SOLD_OUT).claimable)
        assertFalse(award(2).claimable)
        assertFalse(award(0).claimable)
    }

    @Test
    fun `the shop status numbers stay as captured`() {
        assertEquals(1, ExchangeAward.STATUS_AVAILABLE)
        assertEquals(3, ExchangeAward.STATUS_CLAIMED)
        assertEquals(4, ExchangeAward.STATUS_SOLD_OUT)
    }

    @Test
    fun `draws are limited by points`() {
        assertEquals(3, LotteryInfo(point = 100, cost = 30, count = 0, limit = 5).affordable)
        assertEquals(0, LotteryInfo(point = 20, cost = 30, count = 0, limit = 5).affordable)
    }

    @Test
    fun `draws are limited by what is left of the daily limit`() {
        assertEquals(5, LotteryInfo(point = 1000, cost = 10, count = 0, limit = 5).affordable)
        assertEquals(1, LotteryInfo(point = 1000, cost = 10, count = 4, limit = 5).affordable)
        assertEquals(0, LotteryInfo(point = 1000, cost = 10, count = 5, limit = 5).affordable)
    }

    @Test
    fun `more draws than the limit never gives a negative number`() {
        assertEquals(0, LotteryInfo(point = 1000, cost = 10, count = 9, limit = 5).affordable)
    }

    @Test
    fun `a draw that costs nothing or has no price is not offered`() {
        assertEquals(0, LotteryInfo(point = 1000, cost = 0, count = 0, limit = 5).affordable)
        assertEquals(0, LotteryInfo(point = 1000, cost = -5, count = 0, limit = 5).affordable)
    }
}
