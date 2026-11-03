package ru.cherdakmd.chrdktierforge;

import java.util.Objects;

/** Полные копии входов привязаны к конкретному открытому окну и снимку настроек. */
record ForgeAnvilInputs<T>(Object view, T left, T right, Object settings, String rename) {
    boolean matches(Object view,T left,T right,Object settings,String rename) {
        return this.view==view && this.settings==settings && Objects.equals(this.left,left)
                && Objects.equals(this.right,right) && Objects.equals(this.rename,rename);
    }
    static int next(int index,int count) {
        if (count < 1 || index < 0 || index >= count) throw new IllegalArgumentException("Selection");
        return (index+1)%count;
    }
}
