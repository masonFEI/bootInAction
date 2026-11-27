# NoSQL

非关系型数据库，Not Only SQL

bitmaps,节省存储空间

# 内存淘汰策略

## 触发前提

`used_memory` 达到 `maxmemory` 时 Redis 才会启动淘汰，发生在每次写命令执行前（`performEvictions()`）。

> 术语区分：这里是**淘汰策略**（eviction policy），不是**过期**。
>
> - 过期删除：key 的 TTL 到期被删 —— 惰性删除（访问时检查）+ 主动删除（`activeExpireCycle`，每 100ms 随机采样 20 个）
> - 内存淘汰：内存不够时主动牺牲 key —— 统计在 `evicted_keys`

## 8 种 maxmemory-policy

| 策略 | 淘汰范围 | 淘汰依据 | 典型场景 |
| --- | --- | --- | --- |
| `noeviction`（默认） | 不淘汰 | 写命令直接返回 OOM | Redis 同时当 DB 用，数据不能丢 |
| `allkeys-lru` | 所有 key | 最久未使用 | 通用缓存 |
| `allkeys-lfu` | 所有 key | 最不经常使用（Redis 4.0+） | 热点稳定的缓存，抗扫描 |
| `allkeys-random` | 所有 key | 随机 | 性能测试用 |
| `volatile-lru` | 仅带 TTL 的 key | 最久未使用 | 希望旧数据先走 |
| `volatile-lfu` | 仅带 TTL 的 key | 最不经常使用 | 同上 + 抗扫描 |
| `volatile-random` | 仅带 TTL 的 key | 随机 | — |
| `volatile-ttl` | 仅带 TTL 的 key | 剩余 TTL 最短的 | 希望快过期的先走 |

选型速查：

- 纯缓存、数据可丢 → `allkeys-lfu`（热点稳定）/ `allkeys-lru`（热点波动大）
- 数据不能丢 → `noeviction`，靠监控告警 + 扩容
- 想让旧数据先走 → `volatile-*`

## 坑：volatile-* 容易直接 OOM

`volatile-*` 只从**设置了 TTL 的 key** 里淘汰。内存满了但库里有大量没设 TTL 的 key → 一个都不可淘汰 →
写命令直接报 `OOM command not allowed when used memory > 'maxmemory'`。

所以用 `volatile-*` 必须保证**所有 key 都有 TTL**。混合场景（部分 key 无 TTL）用 `volatile-lru` 更危险：
无 TTL 的 key 会永久占着内存且永不被淘汰。

## 底层：近似 LRU，不是严格 LRU

严格 LRU 要给每个对象维护双向链表，每对象多 2 个指针（16 字节），1 亿 key 就是 1.6 GB 额外内存，
而且每次访问都要改指针。Redis 走**近似 LRU**：只复用 `robj` 里已有的 24 bit `lru` 字段，
零额外内存、零指针操作。

`robj.lru` 字段拆解：

```text
┌────────────┬─────────────────────────┐
│  8 bits    │        16 bits          │
│ LRU 时钟 ID│ 最后访问时间戳低 16 位  │
└────────────┴─────────────────────────┘
```

- 低 8 bit：对象所在的 LRU 队列编号，全局共 16 个队列
- 高 16 bit：最后访问时间的 `UNIXTIME` 低 16 位（秒级），约 18.2 小时回绕一次

维护 16 个固定大小为 16 的队列。队列满了就按时间排序，**只把最旧的 1 个移出**——这就是"近似"的来源：
被移出的只是"本队列最旧"，全局可能还有更旧的对象没进这个队列。

淘汰时随机采样 `maxmemory-samples`（默认 5）个 key，每个采样 key 对应的队列里取最旧的，
得到最多 5 × 16 = 80 个候选，再淘汰其中最久未使用的。

| maxmemory-samples | 效果 | 代价 |
| --- | --- | --- |
| 调大（如 50） | 更接近严格 LRU | 每次淘汰扫描更多候选，CPU 开销上升 |
| 调小（如 1） | 快 | 容易淘汰掉本该保留的热门 key |

## 坑：只有读命令会刷新 LRU/LFU 元数据

写路径 `lookupKeyWrite()` 带 `LOOKUP_NOTOUCH` 标记，**故意不更新**元数据；
只有读路径 `lookupKeyRead()` 才会调用 `updateLRU()` / `updateLFU()`。

所以一个持续被写但很少被读的 key，在 LRU 视角下会显得"非常久没使用"，容易被优先淘汰。

## 底层：LFU = 频率 + 时间衰减

纯计数有"历史包袱"：一个曾经爆火的 key 会一直霸占缓存。所以 LFU 用带衰减的 8 bit 计数器（0~255）。

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `lfu-log-factor` | 10 | 对数因子，每 10 次访问计数器才 +1；越大越难被判定为高频 |
| `lfu-decay-time` | 1 分钟 | 衰减周期，超过这个时间没访问就开始衰减 |

衰减公式：

```text
num_adds = (now - last_decay_time) / (lfu_decay_time * lfu_log_factor)
counter  = max(0, counter - num_adds)
```

即每经过 `lfu_decay_time × lfu_log_factor`（默认 10 分钟），计数器 -1。
超过 **1 天**（`LFU_RESET_TIME`）完全没访问 → 计数器直接清零重来。

观察：`OBJECT FREQ mykey` 返回当前计数和上次衰减的时间基准。

## LRU vs LFU 怎么选

| 维度 | LRU | LFU |
| --- | --- | --- |
| 抗扫描攻击 | 差：一次 `KEYS *` 刷新所有 key 的时间戳，把真热点挤出去 | 好：扫描只给每个 key 加一点点计数，热点计数远高于它们 |
| 适应热点漂移 | 好：热点转移后新热点立刻上位 | 慢：新热点要累积到足够高的计数才能翻身 |
| 历史包袱 | 无 | 曾热过的 key 衰减后计数仍偏高，可能挤掉新热点 → 调小 `lfu-decay-time` |
| CPU 开销 | 略高（维护时间戳 + 队列） | 略低（整数运算） |

LFU 翻车案例：运营把商品推上热门 → 计数瞬间打满 255 → 运营撤下热点 →
但它靠衰减前的存量还能霸占缓存好几个小时，新商品上不了热门位。解法就是调小 `lfu-decay-time`。

## 相关配置

```conf
maxmemory 0                       # 0 = 不限制（默认）
maxmemory-policy noeviction
maxmemory-samples 5               # LRU/LFU 采样数
maxmemory-ttl 0                   # key 的 TTL 上限，0 = 无限制
maxmemory-eviction-tenacity 10    # Redis 7.0+：淘汰的"狠心程度"，越大越急于释放内存
```

## 监控

```bash
INFO memory | grep evicted_keys   # 淘汰数，持续增长 = 内存不够，雪崩前兆
INFO stats  | grep expired_keys  # 过期删除数，和淘汰是两回事
CONFIG GET maxmemory*
```

常用配置模板：

```conf
# 纯缓存，允许丢
maxmemory 8gb
maxmemory-policy allkeys-lfu
maxmemory-samples 10

# 数据不能丢
maxmemory-policy noeviction
```

通过乐观锁，来避免商品的超卖；监视库存，放在事务中执行。或者放在lua脚本中

## 乐观锁防超卖：WATCH + MULTI/EXEC

「监视库存」用的就是 Redis 的 **WATCH** 命令——把库存 key 挂上监视，事务提交前只要它被别人改过，整个事务就被作废（`EXEC` 返回 `nil`），需要重读重试。

### 1. 原生命令（redis-cli 交互模式）

```text
WATCH stock:1001          # 1. 监视库存 key
GET stock:1001            # 2. 读出当前库存（必须在 MULTI 之前，才是"先读后算"）
MULTI                     # 3. 开启事务
DECR stock:1001           # 4. 扣减
EXEC                      # 5. 提交
```

- `EXEC` 返回 `nil` → WATCH 期间 key 被别人改过 → 事务作废，重新 WATCH 重来
- `UNWATCH` 取消监视；`DISCARD` 放弃已开启的事务
- 只能监视同一个 key（Cluster 模式还要求同一 slot，key 写成 `stock:{1001}`）
- 注意：`DECR` 本身不防超卖（会扣成负数），"判断库存 + 扣减"必须原子化，这才是 WATCH 的意义

### 2. Spring Data Redis（Spring Boot 2.4 + Lettuce）

```java
@Service
public class StockService {

    private static final String KEY_PREFIX = "stock:";
    private static final int MAX_RETRY = 3;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * WATCH + MULTI/EXEC 乐观锁扣减库存；EXEC 被中止时重试。
     */
    public boolean deduct(String sku, int count) {
        String key = KEY_PREFIX + sku;
        for (int i = 0; i < MAX_RETRY; i++) {
            Boolean result = redisTemplate.execute((RedisCallback<Boolean>) connection -> {
                byte[] k = key.getBytes(StandardCharsets.UTF_8);
                connection.watch(k);                                   // 1. 监视库存
                byte[] raw = connection.get(k);                        // 2. 读库存
                int stock = raw == null ? 0
                        : Integer.parseInt(new String(raw, StandardCharsets.UTF_8));
                if (stock < count) {
                    connection.unwatch();                             // 库存不足，直接放弃
                    return false;
                }
                connection.multi();                                    // 3. 开事务
                connection.set(k, String.valueOf(stock - count)
                        .getBytes(StandardCharsets.UTF_8));
                List<Object> exec = connection.exec();                 // 4. 提交；被改过则返回 null
                return exec != null;                                  // null = 事务被中止 → 要重试
            });
            if (Boolean.TRUE.equals(result)) {
                return true;
            }
            if (Boolean.FALSE.equals(result)) {
                return false;            // 库存不足，不用重试
            }
            // result == null：WATCH 失效，进入下一轮重试
        }
        return false;
    }
}
```

坑：

- WATCH/MULTI 是**连接级**状态，必须保证这几条命令走**同一条连接**。用 `RedisTemplate.execute(RedisCallback)`
  时框架会绑定连接；Lettuce 默认共享连接，必要时把 `LettuceConnectionFactory.setShareNativeConnection(false)`，
  或用独立连接池，避免 WATCH 状态被其他线程串味。
- 官方文档的等价写法是 `SessionCallback`：`operations.watch(key)` → `operations.multi()` → `operations.exec()`。

### 3. 更推荐的写法：Lua 脚本（原子，无需重试）

```java
private static final DefaultRedisScript<Long> DEDUCT_SCRIPT = new DefaultRedisScript<>(
        "local stock = tonumber(redis.call('GET', KEYS[1])) " +
        "if not stock or stock <= 0 then return 0 end " +
        "redis.call('DECR', KEYS[1]) " +
        "return stock - 1",
        Long.class);

public long deductWithLua(String sku) {
    Long left = redisTemplate.execute(DEDUCT_SCRIPT,
            Collections.singletonList(KEY_PREFIX + sku));
    return left == null ? 0 : left;   // 0 表示库存不足
}
```

- Redis 执行脚本期间不穿插其它命令，天然原子，不用 WATCH、不用重试，没有"读-算-写"的竞态窗口
- 脚本要短；Cluster 模式下 KEYS 里的 key 必须同一 slot（`stock:{1001}`）
- 防超卖经典配套是"Redis 预扣减 + 异步落库"，Lua 里同时写库存和下单流水

### 4. 三种方案对比

| 方案 | 原子粒度 | 是否重试 | 适用 |
| --- | --- | --- | --- |
| WATCH + MULTI/EXEC | 事务级 | 需要 | 乐观锁，实现简单，有竞态重试开销 |
| Lua 脚本 | 单命令级 | 不需要 | 原子性最强，生产首选 |
| SETNX 分布式锁 | 互斥级 | 看业务 | 悲观锁，见下半部分「分布式锁」，适合复杂扣减逻辑 |


redis 持久化技术

RDB与AOF

# 主从复制

一主多从，主节点写，从节点读为主；

好处

1. 读写分离，性能扩展
2. 容灾快速恢复

## 哨兵模式

反客为主的自动版，发现主节点有故障，从节点们立马投票选一个从节点为主节点；
选出优先级配置（slave-priority）最小的,runId最小的（启动时生成的随机UUID）

# 集群模式

无中心化集群

无中心化集群，节点之间通过 Gossip 协议通信，数据分片存储。
实现了对数据的水平扩容。每个节点存储1/n的数据。

### 数据分片

Redis Cluster 将键空间划分为 16384 个哈希槽，每个主节点负责一部分槽。客户端通过 CRC16(key) % 16384
计算键所属槽位；存在哈希标签时，只对标签内容计算，例如 `{user:1}`。

### 故障转移

集群节点之间相互通信并通过 Consensus 协议达成一致。当主节点故障

# 缓存穿透

1. 对空值缓存
2. 布隆过滤器，bitmaps

# 缓存雪崩

将缓存失效时间分散开

# 分布式锁

开启锁：SETEX命令
上锁的时候，设置过期时间

释放锁：del命令

a上锁后，服务器卡顿，然后自动释放；等反应过来后，会有释放b锁的问题，需要加上UUID避免释放错锁

redis6依然是单线程（执行命令的部分）+ 多路IO复用

