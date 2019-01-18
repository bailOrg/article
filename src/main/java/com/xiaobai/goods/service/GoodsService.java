package com.xiaobai.goods.service;

import com.xiaobai.common.base.CommonRuntimeException;
import com.xiaobai.common.base.ErrorCode;
import com.xiaobai.goods.dao.IGoodsDao;
import com.xiaobai.goods.entity.Goods;
import com.xiaobai.util.SleepUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品库存Service
 *
 * @author bail
 * @date 2018/4/17.15:39
 */
@Service
@Slf4j
public class GoodsService {
    @Autowired
    private IGoodsDao goodsDao;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private AsyncService asyncService;

    @Transactional(rollbackFor = Exception.class)
    public List<Goods> queryGoodsList(Goods goods) {
        log.info("1---主方法执行开始...");
        for (int i = 0; i < 20; i++) {
            asyncService.testAsyncFunc();
        }
        log.info("2---主方法执行结束...");
        return goodsDao.queryGoodsList(goods);
    }

    @Transactional(rollbackFor = Exception.class)
    public Goods queryGoods(Long id) {
        return goodsDao.queryGoods(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Goods updateGoodsLocked(Goods goods) {
        //更新库存-使用redis分布式锁,保证当前service方法顺序执行(即使集群部署也能保证)
        Goods after;
        RLock rLock = redissonClient.getFairLock(String.format("lock_%s", goods.getGoodsInfoId().toString()));
        rLock.lock();
        try {
            Goods before = goodsDao.queryGoods(goods.getGoodsInfoId());
            SleepUtil.sleepSomeTime(10000);
            if (before.getStock() >= goods.getStock()) {
                goods.setStock(before.getStock() - goods.getStock());
                goodsDao.updateGoods(goods);
                after = goodsDao.queryGoods(goods.getGoodsInfoId());
            } else {
                throw new CommonRuntimeException(ErrorCode.NO_ENOUGH_STOCK);
            }
        } finally {
            rLock.unlock();
        }
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public Goods updateGoods(Goods goods) {
        //更新库存-使用mysql innodb , 行锁启用(主键索引), 将顺序执行该行的update语句
        int updateCount = goodsDao.subtractGoodsStock(goods);
        log.info("本次更新的条数:{}", updateCount);
        SleepUtil.sleepSomeTime(10000);
        if (updateCount < 1) {
            throw new CommonRuntimeException(ErrorCode.NO_ENOUGH_STOCK);
        }

        //测试是否能够查询到修改后的库存
        Goods after = goodsDao.queryGoods(goods.getGoodsInfoId());
        log.info("执行之后数据为:{}", after);
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public int insertGoodsList(List<Goods> goodsList) {
        int count = goodsDao.insertGoodsList(goodsList);
        log.info("本次插入的条数:{}", count);
        return count;
    }

}
