package org.jasig.cas.util;

import org.jasig.cas.ticket.Ticket;
import org.jasig.cas.ticket.TicketGrantingTicket;
import org.jasig.cas.ticket.registry.AbstractDistributedTicketRegistry;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 使用Redis存储Ticket
 *
 */
public class RedisTicketRegistry extends AbstractDistributedTicketRegistry {

    private static RedisTemplate<String, Ticket> redisTemplate;

    private static RedisTemplate<String, String> userTgsTemplate;

    public static final String WHOLE_TGT_LIST = "OPS_WHOLE_TGT_LIST";


    /**
     * ST最大空闲时间
     */
    private int st_time;

    /**
     * TGT最大空闲时间
     */
    private int tgt_time;

    private static final TimeUnit timeUnit = TimeUnit.SECONDS;

    public void setRedisTemplate(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.userTgsTemplate = redisTemplate;
    }

    @Override
    public void addTicket(Ticket ticket) {
        try {
            int seconds;
            final String key = ticket.getId();
            redisTemplate.opsForValue().set(key, ticket);
            logger.info("add ticket:{}", key);
            if (ticket instanceof TicketGrantingTicket) {
                seconds = tgt_time;
                addToTgtList(ticket.getId());
                addUserTicket(ticket);
            } else {
                seconds = st_time;
            }
            redisTemplate.expire(key, seconds, timeUnit);
        }catch (Exception e){
            logger.error("addTicket",e);
        }

    }

    @Override
    public boolean deleteTicket(String ticketId) {
        try {
            if (ticketId == null) {
                return false;
            }
            Ticket ticket = getTicket(ticketId);
            if (ticket instanceof TicketGrantingTicket) {
                deleteUserTicket(ticket);
                deleteFromTgtList(ticketId);
            }
            redisTemplate.delete(ticketId);
//            redisTemplate.opsForValue().set(ticketId, null);
            logger.info("delete ticket:{}", ticketId);
        }catch (Exception e){
            logger.error("deleteTicket",e);
            return false;
        }
        return true;
    }

    @Override
    public Ticket getTicket(String ticketId) {
        return getProxiedTicketInstance(getRawTicket(ticketId));
    }

    private Ticket getRawTicket(final String ticketId) {
        try {
            if (null == ticketId) {
                return null;
            }
            Ticket ticket = redisTemplate.opsForValue().get(ticketId);
            logger.info("get ticket:{}", ticketId);
            return ticket;
        }catch (Exception e){
            logger.error("getRawTicket",e);
        }
        return null;
    }

    /**
     * 获取所有的票据，票据过期的关键步骤
     * @return
     */
    @Override
    public Collection<Ticket> getTickets() {
        List<Ticket> tickets;
        try {
            //获取所有TGT.id
            List<String> allTgts = userTgsTemplate.opsForList().range(
                    WHOLE_TGT_LIST, 0, userTgsTemplate.opsForList().size(WHOLE_TGT_LIST));
            if(allTgts==null){
                return null;
            }
            //获取所有TGT ticket对象
            tickets = redisTemplate.opsForValue().multiGet(allTgts);
            //重新构建新的 WHOLE_TGT_LIST
            List<String> allTgtsArr = new ArrayList<>();
            Iterator<Ticket> iterator = tickets.iterator();
            Ticket ticket;
            while (iterator.hasNext()) {
                ticket = iterator.next();
                if (ticket == null) {
                    iterator.remove();
                    continue;
                }
                allTgtsArr.add(ticket.getId());
            }
            String[] results = new String[allTgtsArr.size()];
            allTgtsArr.toArray(results);

            //清空重写 WHOLE_TGT_LIST
            userTgsTemplate.delete(WHOLE_TGT_LIST);
            if(results!=null || results.length==0) {
                userTgsTemplate.opsForList().leftPushAll(WHOLE_TGT_LIST, results);
            }
            logger.info("{}:{}", WHOLE_TGT_LIST, allTgtsArr.size());
            return tickets;
        }catch (Exception e){
            logger.error("getTickets",e);
        }
        tickets = new ArrayList<>();
        return tickets;
    }

    @Override
    protected boolean needsCallback() {
        return false;
    }

    @Override
    protected void updateTicket(Ticket ticket) {
        this.addTicket(ticket);
    }

    /**
     * 添加 user-tgt 对应数据
     * 一个用户对应多个tgt，每个tgt看做一个客户端
     * @param ticket
     */
    private void addUserTicket(Ticket ticket) {
        deleteUserTicket(ticket);
        String loginName = ((TicketGrantingTicket) ticket).getAuthentication().
                getPrincipal().getId();
        userTgsTemplate.opsForSet().add(loginName,ticket.getId());
        userTgsTemplate.expire(loginName,tgt_time,timeUnit);
    }

    /**
     * 删除 user-tgt 对应数据
     * @param ticket
     */
    private void deleteUserTicket(Ticket ticket) {
        if(ticket==null) return;
        if (ticket instanceof TicketGrantingTicket) {
            String loginName = ((TicketGrantingTicket) ticket).getAuthentication().
                    getPrincipal().getId();
            userTgsTemplate.opsForSet().remove(loginName,ticket.getId());
        }
    }

    /**
     * 添加tgtID到tgt池
     * @param ticketId
     */
    private void addToTgtList(String ticketId){
        deleteFromTgtList(ticketId);
        userTgsTemplate.opsForList().rightPush(WHOLE_TGT_LIST, ticketId);
        logger.info("{}.size = {}",WHOLE_TGT_LIST,userTgsTemplate.opsForList().size(WHOLE_TGT_LIST));
    }

    /**
     * 从tgt池删除tgtID
     * @param ticketId
     */
    private void deleteFromTgtList(String ticketId){
        userTgsTemplate.opsForList().remove(WHOLE_TGT_LIST,0,ticketId);
        logger.info("{}.size = {}",WHOLE_TGT_LIST,userTgsTemplate.opsForList().size(WHOLE_TGT_LIST));
    }

    public void cleanTicket(){
        userTgsTemplate.delete(WHOLE_TGT_LIST);
    }


}
