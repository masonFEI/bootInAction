# NoSQL

非关系型数据库，Not Only SQL

bitmaps,节省存储空间

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

