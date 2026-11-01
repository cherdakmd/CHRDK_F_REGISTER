package ru.cherdakmd.chrdkbp;

/** Фильтр не изменяет цели, прогресс и права на награду. */
enum BattlePassFilter {
    ALL, ACTIVE, READY, FUTURE, DONE;
    BattlePassFilter next(){return values()[(ordinal()+1)%values().length];}
    boolean includes(boolean unlocked,BattlePassView.Mission mission) {
        return switch(this) {
            case ALL->true;
            case ACTIVE->unlocked&&!mission.goalReached();
            case READY->unlocked&&mission.state()==BattlePassView.State.READY;
            case FUTURE->!unlocked;
            case DONE->unlocked&&mission.goalReached();
        };
    }
}
