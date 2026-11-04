// Since API 6. Curbox calls this when something a client reads has changed, so it need not poll.
package neth.iecal.curbox.api;

oneway interface ICurboxListener {
    // what is FOCUS when the focus session or the focus groups changed.
    void onChanged(String what);
}
