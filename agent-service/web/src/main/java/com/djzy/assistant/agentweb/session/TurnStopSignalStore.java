package com.djzy.assistant.agentweb.session;

/**
 * 「这一轮请停下来」的信号通道（§19.4 停止）。
 *
 * <p>为什么不能只用进程内的会话句柄：用户点「停止」时，这个请求可能被打到**另一个实例**上，
 * 而真正在跑这一轮的实例不在这台机器上。所以信号要放到两边都看得见的地方（Redis），
 * 跑到一半的那个实例会在下一个事件上看到它，然后把运行时取消掉。
 *
 * <p>信号按「会话 + 那一轮」定位，所以停的是**一轮**，不是整个会话：
 * 会话本身、以及这一轮之前已经产出的内容都原样留着，用户可以接着问下一轮。
 *
 * <p>信号必须带 TTL：它只是「这一刻有人喊停」，不是一条长期状态。留久了会误伤后面同会话的轮次。
 */
public interface TurnStopSignalStore {

    /** 广播「请停掉这一轮」。重复调用无副作用（幂等）。 */
    void request(String sessionId, String turnId);

    /**
     * 这一轮有没有人喊停。
     *
     * <p>这个方法会被**每个流事件**问一次（一轮下来几十到几百次），所以实现要尽量便宜；
     * 换来的是「无论停止请求落到哪个实例，都能在很短的时间内停下来」。
     */
    boolean isRequested(String sessionId, String turnId);

    /** 信号已经兑现（这一轮真的停了）之后清掉它，避免残留。 */
    void clear(String sessionId, String turnId);
}
