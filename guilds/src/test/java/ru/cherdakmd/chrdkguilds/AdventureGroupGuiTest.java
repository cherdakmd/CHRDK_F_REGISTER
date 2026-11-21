package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class AdventureGroupGuiTest {
    @Test void memberAndInviteLayoutsAreDistinctAndNeverHitControls() {
        var members = new HashSet<Integer>();
        for(int i=0;i<16;i++) assertTrue(members.add(AdventureGroupGui.memberSlot(i)));
        assertFalse(members.contains(37)); assertFalse(members.contains(39)); assertFalse(members.contains(41));
        assertFalse(members.contains(43)); assertFalse(members.contains(49));
        var invitations = new HashSet<Integer>();
        for(int i=0;i<28;i++) assertTrue(invitations.add(AdventureGroupGui.invitationSlot(i)));
        assertFalse(invitations.contains(45)); assertFalse(invitations.contains(49)); assertFalse(invitations.contains(53));
        assertThrows(IllegalArgumentException.class,()->AdventureGroupGui.memberSlot(16));
        assertThrows(IllegalArgumentException.class,()->AdventureGroupGui.invitationSlot(28));
    }
}
