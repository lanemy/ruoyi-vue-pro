package cn.iocoder.yudao.module.system.service.social;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.bean.WxMaPhoneNumberInfo;
import cn.binarywang.wx.miniapp.config.impl.WxMaRedisBetterConfigImpl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ReflectUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.cache.CacheUtils;
import cn.iocoder.yudao.framework.common.util.http.HttpUtils;
import cn.iocoder.yudao.framework.social.core.YudaoAuthRequestFactory;
import cn.iocoder.yudao.module.system.controller.admin.socail.vo.client.SocialClientCreateReqVO;
import cn.iocoder.yudao.module.system.controller.admin.socail.vo.client.SocialClientPageReqVO;
import cn.iocoder.yudao.module.system.controller.admin.socail.vo.client.SocialClientUpdateReqVO;
import cn.iocoder.yudao.module.system.convert.social.SocialClientConvert;
import cn.iocoder.yudao.module.system.dal.dataobject.social.SocialClientDO;
import cn.iocoder.yudao.module.system.dal.mysql.social.SocialClientMapper;
import cn.iocoder.yudao.module.system.enums.social.SocialTypeEnum;
import com.binarywang.spring.starter.wxjava.miniapp.properties.WxMaProperties;
import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.xingyuv.jushauth.config.AuthConfig;
import com.xingyuv.jushauth.model.AuthCallback;
import com.xingyuv.jushauth.model.AuthResponse;
import com.xingyuv.jushauth.model.AuthUser;
import com.xingyuv.jushauth.request.AuthRequest;
import com.xingyuv.jushauth.utils.AuthStateUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.bean.WxJsapiSignature;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.common.redis.RedisTemplateWxRedisOps;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import me.chanjar.weixin.mp.config.impl.WxMpRedisConfigImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.*;

/**
 * 社交应用 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Slf4j
public class SocialClientServiceImpl implements SocialClientService {

    @Resource // 由于自定义了 YudaoAuthRequestFactory 无法覆盖默认的 AuthRequestFactory，所以只能注入它
    private YudaoAuthRequestFactory yudaoAuthRequestFactory;

    @Resource
    private WxMpService wxMpService;
    @Resource
    private WxMpProperties wxMpProperties;
    @Resource
    private StringRedisTemplate stringRedisTemplate; // WxMpService 需要使用到，所以在 Service 注入了它
    /**
     * 缓存 WxMpService 对象
     *
     * key：使用微信公众号的 appId + secret 拼接，即 {@link SocialClientDO} 的 clientId 和 clientSecret 属性。
     * 为什么 key 使用这种格式？因为 {@link SocialClientDO} 在管理后台可以变更，通过这个 key 存储它的单例。
     *
     * 为什么要做 WxMpService 缓存？因为 WxMpService 构建成本比较大，所以尽量保证它是单例。
     */
    private final LoadingCache<String, WxMpService> wxMpServiceCache = CacheUtils.buildAsyncReloadingCache(
            Duration.ofSeconds(10L),
            new CacheLoader<String, WxMpService>() {

                @Override
                public WxMpService load(String key) {
                    String[] keys = key.split(":");
                    return buildWxMpService(keys[0], keys[1]);
                }

            });

    @Resource
    private WxMaService wxMaService;
    @Resource
    private WxMaProperties wxMaProperties;
    /**
     * 缓存 WxMaService 对象
     *
     * 说明同 {@link #wxMpServiceCache} 变量
     */
    private final LoadingCache<String, WxMaService> wxMaServiceCache = CacheUtils.buildAsyncReloadingCache(
            Duration.ofSeconds(10L),
            new CacheLoader<String, WxMaService>() {

                @Override
                public WxMaService load(String key) {
                    String[] keys = key.split(":");
                    return buildWxMaService(keys[0], keys[1]);
                }

            });

    @Resource
    private SocialClientMapper socialClientMapper;

    @Override
    public String getAuthorizeUrl(Integer socialType, Integer userType, String redirectUri) {
        // 获得对应的 AuthRequest 实现
        AuthRequest authRequest = buildAuthRequest(socialType, userType);
        // 生成跳转地址
        String authorizeUri = authRequest.authorize(AuthStateUtils.createState());
        return HttpUtils.replaceUrlQuery(authorizeUri, "redirect_uri", redirectUri);
    }

    @Override
    public AuthUser getAuthUser(Integer socialType, Integer userType, String code, String state) {
        // 构建请求
        AuthRequest authRequest = buildAuthRequest(socialType, userType);
        AuthCallback authCallback = AuthCallback.builder().code(code).state(state).build();
        // 执行请求
        AuthResponse<?> authResponse = authRequest.login(authCallback);
        log.info("[getAuthUser][请求社交平台 type({}) request({}) response({})]", socialType,
                toJsonString(authCallback), toJsonString(authResponse));
        if (!authResponse.ok()) {
            throw exception(SOCIAL_USER_AUTH_FAILURE, authResponse.getMsg());
        }
        return (AuthUser) authResponse.getData();
    }

    /**
     * 构建 AuthRequest 对象，支持多租户配置
     *
     * @param socialType 社交类型
     * @param userType 用户类型
     * @return AuthRequest 对象
     */
    private AuthRequest buildAuthRequest(Integer socialType, Integer userType) {
        // 1. 先查找默认的配置项，从 application-*.yaml 中读取
        AuthRequest request = yudaoAuthRequestFactory.get(SocialTypeEnum.valueOfType(socialType).getSource());
        Assert.notNull(request, String.format("社交平台(%d) 不存在", socialType));
        // 2. 查询 DB 的配置项，如果存在则进行覆盖
        SocialClientDO client = socialClientMapper.selectBySocialTypeAndUserType(socialType, userType);
        if (client != null && Objects.equals(client.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
            // 2.1 构造新的 AuthConfig 对象
            AuthConfig authConfig = (AuthConfig) ReflectUtil.getFieldValue(request, "config");
            AuthConfig newAuthConfig = ReflectUtil.newInstance(authConfig.getClass());
            BeanUtil.copyProperties(authConfig, newAuthConfig);
            // 2.2 修改对应的 clientId + clientSecret 密钥
            newAuthConfig.setClientId(client.getClientId());
            newAuthConfig.setClientSecret(client.getClientSecret());
            // 2.3 设置会 request 里，进行后续使用
            ReflectUtil.setFieldValue(request, "config", newAuthConfig);
        }
        return request;
    }

    // =================== 微信公众号独有 ===================

    @Override
    @SneakyThrows
    public WxJsapiSignature createWxMpJsapiSignature(Integer userType, String url) {
        WxMpService service = getWxMpService(userType);
        return service.createJsapiSignature(url);
    }

    /**
     * 获得 clientId + clientSecret 对应的 WxMpService 对象
     *
     * @param userType 用户类型
     * @return WxMpService 对象
     */
    private WxMpService getWxMpService(Integer userType) {
        // 第一步，查询 DB 的配置项，获得对应的 WxMpService 对象
        SocialClientDO client = socialClientMapper.selectBySocialTypeAndUserType(
                SocialTypeEnum.WECHAT_MP.getType(), userType);
        if (client != null && Objects.equals(client.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
            return wxMpServiceCache.getUnchecked(client.getClientId() + ":" + client.getClientSecret());
        }
        // 第二步，不存在 DB 配置项，则使用 application-*.yaml 对应的 WxMpService 对象
        return wxMpService;
    }

    /**
     * 创建 clientId + clientSecret 对应的 WxMpService 对象
     *
     * @param clientId 微信公众号 appId
     * @param clientSecret 微信公众号 secret
     * @return WxMpService 对象
     */
    private WxMpService buildWxMpService(String clientId, String clientSecret) {
        // 第一步，创建 WxMpRedisConfigImpl 对象
        WxMpRedisConfigImpl configStorage = new WxMpRedisConfigImpl(
                new RedisTemplateWxRedisOps(stringRedisTemplate),
                wxMpProperties.getConfigStorage().getKeyPrefix());
        configStorage.setAppId(clientId);
        configStorage.setSecret(clientSecret);

        // 第二步，创建 WxMpService 对象
        WxMpService service = new WxMpServiceImpl();
        service.setWxMpConfigStorage(configStorage);
        return service;
    }

    // =================== 微信小程序独有 ===================

    @Override
    public WxMaPhoneNumberInfo getWxMaPhoneNumberInfo(Integer userType, String phoneCode) {
        WxMaService service = getWxMaService(userType);
        try {
            return service.getUserService().getPhoneNoInfo(phoneCode);
        } catch (WxErrorException e) {
            log.error("[getPhoneNoInfo][userType({}) phoneCode({}) 获得手机号失败]", userType, phoneCode, e);
            throw exception(SOCIAL_APP_WEIXIN_MINI_APP_PHONE_CODE_ERROR);
        }
    }

    /**
     * 获得 clientId + clientSecret 对应的 WxMpService 对象
     *
     * @param userType 用户类型
     * @return WxMpService 对象
     */
    private WxMaService getWxMaService(Integer userType) {
        // 第一步，查询 DB 的配置项，获得对应的 WxMaService 对象
        SocialClientDO client = socialClientMapper.selectBySocialTypeAndUserType(
                SocialTypeEnum.WECHAT_MINI_APP.getType(), userType);
        if (client != null && Objects.equals(client.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
            return wxMaServiceCache.getUnchecked(client.getClientId() + ":" + client.getClientSecret());
        }
        // 第二步，不存在 DB 配置项，则使用 application-*.yaml 对应的 WxMaService 对象
        return wxMaService;
    }

    /**
     * 创建 clientId + clientSecret 对应的 WxMaService 对象
     *
     * @param clientId 微信小程序 appId
     * @param clientSecret 微信小程序 secret
     * @return WxMaService 对象
     */
    private WxMaService buildWxMaService(String clientId, String clientSecret) {
        // 第一步，创建 WxMaRedisBetterConfigImpl 对象
        WxMaRedisBetterConfigImpl configStorage = new WxMaRedisBetterConfigImpl(
                new RedisTemplateWxRedisOps(stringRedisTemplate),
                wxMaProperties.getConfigStorage().getKeyPrefix());
        configStorage.setAppid(clientId);
        configStorage.setSecret(clientSecret);

        // 第二步，创建 WxMpService 对象
        WxMaService service = new WxMaServiceImpl();
        service.setWxMaConfig(configStorage);
        return service;
    }

    // =================== 客户端管理 ===================

    @Override
    public Long createSocialClient(SocialClientCreateReqVO createReqVO) {
        // 插入
        SocialClientDO socialClient = SocialClientConvert.INSTANCE.convert(createReqVO);
        socialClientMapper.insert(socialClient);
        // 返回
        return socialClient.getId();
    }

    @Override
    public void updateSocialClient(SocialClientUpdateReqVO updateReqVO) {
        // 校验存在
        validateSocialClientExists(updateReqVO.getId());
        // 更新
        SocialClientDO updateObj = SocialClientConvert.INSTANCE.convert(updateReqVO);
        socialClientMapper.updateById(updateObj);
    }

    @Override
    public void deleteSocialClient(Long id) {
        // 校验存在
        validateSocialClientExists(id);
        // 删除
        socialClientMapper.deleteById(id);
    }

    private void validateSocialClientExists(Long id) {
        if (socialClientMapper.selectById(id) == null) {
            throw exception(SOCIAL_CLIENT_NOT_EXISTS);
        }
    }

    @Override
    public SocialClientDO getSocialClient(Long id) {
        return socialClientMapper.selectById(id);
    }

    @Override
    public List<SocialClientDO> getSocialClientList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return ListUtil.empty();
        }
        return socialClientMapper.selectBatchIds(ids);
    }

    @Override
    public PageResult<SocialClientDO> getSocialClientPage(SocialClientPageReqVO pageReqVO) {
        return socialClientMapper.selectPage(pageReqVO);
    }

}
