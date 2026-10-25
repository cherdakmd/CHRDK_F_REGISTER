package ru.cherdakmd.chrdkregister;

/** Событие, по которому игрок может появиться в случайной точке. */
public enum SpawnTrigger {
    /** Первый заход: игрок впервые завершил вход на сервер. */
    FIRST_JOIN,
    /** Смерть: игрок нажал «Возродиться». */
    DEATH
}
