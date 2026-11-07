package rongyi.lingbai;

/** Legacy-shaped callback adapted to the libxposed API 102 interceptor chain. */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    final void callBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    final void callAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        void setOutcome(Object result, Throwable throwable, boolean returnEarly) {
            this.result = result;
            this.throwable = throwable;
            this.returnEarly = returnEarly;
        }
    }
}
