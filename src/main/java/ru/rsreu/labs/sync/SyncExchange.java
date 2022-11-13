package ru.rsreu.labs.sync;

import ru.rsreu.labs.Exchange;
import ru.rsreu.labs.exceptions.NotEnoughMoneyException;
import ru.rsreu.labs.models.*;
import ru.rsreu.labs.repositories.ClientBalanceRepository;
import ru.rsreu.labs.repositories.ConcurrentClientBalanceRepository;
import ru.rsreu.labs.repositories.OrderRepository;
import ru.rsreu.labs.utils.BigDecimalUtils;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ThreadSafe
public class SyncExchange implements Exchange {
    private final OrderRepository orderRepository = new OrderRepository();
    private final ClientBalanceRepository clientBalanceRepository = new ConcurrentClientBalanceRepository();

    private final AtomicLong coverCount = new AtomicLong(0);

    @Override
    public Client createClient() {
        Client client = new Client();
        clientBalanceRepository.addClient(client);
        return client;
    }

    @Override
    public void pushMoney(Client client, Currency currency, BigDecimal value) {
        clientBalanceRepository.pushMoney(client, currency, value);
    }

    @Override
    public void takeMoney(Client client, Currency currency, BigDecimal value) throws NotEnoughMoneyException {
        boolean isSuccess = clientBalanceRepository.tryTakeMoney(client, currency, value);
        if (!isSuccess) {
            throw new NotEnoughMoneyException();
        }
    }

    @Override
    public void createOrder(Order order) throws NotEnoughMoneyException {
        takeMoney(order.getClient(), order.getSourceCurrency(), order.getSourceValue());
        boolean isCovered = tryFindAndCoverOrder(order);
        if (!isCovered) {
            List<OrderInfo> orders = orderRepository.getOrdersByCurrencyPair(order.getCurrencyPair());
            synchronized (orders) {
                orders.add(order.getOrderInfo());
            }
        }
    }

    private boolean tryFindAndCoverOrder(Order order) {
        List<OrderInfo> orders = orderRepository.getOrdersByCurrencyPair(order.getCurrencyPair().inverse());
        synchronized (orders) {
            Optional<OrderInfo> coverOrder = getCoveredOrder(order.getOrderInfo(), orders);
            if (coverOrder.isPresent()) {
                coverOrders(order.getSourceCurrency(), order.getTargetCurrency(), order.getOrderInfo(), coverOrder.get());
                orders.remove(coverOrder.get());
                return true;
            }
            return false;
        }
    }

    private Optional<OrderInfo> getCoveredOrder(OrderInfo newOrder, List<OrderInfo> orders) {
        if (orders.size() == 0) return Optional.empty();
        OrderInfo bestOrderInfo = orders.get(0);
        for (OrderInfo orderInfo : orders) {
            if (bestOrderInfo.getSourceToTargetRate().compareTo(orderInfo.getSourceToTargetRate()) <= 0) {
                bestOrderInfo = orderInfo;
            }
        }
        if (bestOrderInfo.getSourceToTargetRate().compareTo(newOrder.getTargetToSourceRate()) >= 0) {
            return Optional.of(bestOrderInfo);
        } else {
            return Optional.empty();
        }
    }

    private void coverOrders(Currency sourceCurrency, Currency targetCurrency, OrderInfo newOrderInfo, OrderInfo oldOrderInfo) {
        BigDecimal targetCurrencyOrderSum = oldOrderInfo.getSourceValue().min(newOrderInfo.getTargetValue());
        BigDecimal sourceCurrencyOrderSum = BigDecimalUtils.getValueByRate(targetCurrencyOrderSum, oldOrderInfo.getTargetToSourceRate());

        BigDecimal newOrderCashback = newOrderInfo.getSourceValue().subtract(sourceCurrencyOrderSum);
        clientBalanceRepository.pushMoney(newOrderInfo.getClient(), sourceCurrency, newOrderCashback);
        clientBalanceRepository.pushMoney(newOrderInfo.getClient(), targetCurrency, targetCurrencyOrderSum);

        BigDecimal oldOrderCashback = oldOrderInfo.getSourceValue().subtract(targetCurrencyOrderSum);
        clientBalanceRepository.pushMoney(oldOrderInfo.getClient(), targetCurrency, oldOrderCashback);
        clientBalanceRepository.pushMoney(oldOrderInfo.getClient(), sourceCurrency, sourceCurrencyOrderSum);
        coverCount.incrementAndGet();
    }

    @Override
    public List<Order> getOpenOrders() {
        List<Order> openOrders = new ArrayList<>();
        Map<CurrencyPair, List<OrderInfo>> ordersMap = orderRepository.getOrders();
        ordersMap.forEach((currencyPair, orders) -> {
            synchronized (orders) {
                orders.forEach(order -> openOrders.add(new Order(currencyPair, order)));
            }
        });
        return openOrders;
    }

    @Override
    public Balance getClientBalance(Client client) {
        return clientBalanceRepository.getClientBalance(client);
    }

    @Override
    public Balance getGeneralBalance() {
        Balance clientBalance = clientBalanceRepository.getGeneralClientsBalance();
        System.out.println("client :" + clientBalance);
        Balance openOrdersCost = getOpenOrdersCost();
        System.out.println("orders :" + openOrdersCost);
        return clientBalance.add(openOrdersCost);
    }

    @Override
    public long getCoverCount() {
        return coverCount.get();
    }

    private Balance getOpenOrdersCost() {
        ConcurrentHashMap<Currency, BigDecimal> result = new ConcurrentHashMap<>();
        List<Order> openOrders = getOpenOrders();
        openOrders.forEach(order -> result.compute(order.getSourceCurrency(),
                (key, value) -> result.getOrDefault(key, BigDecimal.ZERO).add(
                        order.getSourceValue())));
        return new Balance(result);
    }
}