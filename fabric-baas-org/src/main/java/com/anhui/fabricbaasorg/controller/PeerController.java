package com.anhui.fabricbaasorg.controller;

import com.anhui.fabricbaascommon.constant.Authority;
import com.anhui.fabricbaascommon.request.BaseChannelRequest;
import com.anhui.fabricbaascommon.request.PaginationQueryRequest;
import com.anhui.fabricbaascommon.response.ListResult;
import com.anhui.fabricbaascommon.response.PageResult;
import com.anhui.fabricbaascommon.response.UniqueResult;
import com.anhui.fabricbaasorg.bean.ChannelPeer;
import com.anhui.fabricbaasorg.entity.PeerEntity;
import com.anhui.fabricbaasorg.remote.TTPChannelApi;
import com.anhui.fabricbaasorg.request.BasePeerRequest;
import com.anhui.fabricbaasorg.request.PeerStartRequest;
import com.anhui.fabricbaasorg.service.KubernetesService;
import com.anhui.fabricbaasorg.service.PeerService;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/peer")
@Api(tags = "Peer管理模块", value = "Peer管理相关接口")
public class PeerController {
    @Autowired
    private PeerService peerService;
    @Autowired
    private TTPChannelApi ttpChannelApi;
    @Autowired
    private KubernetesService kubernetesService;

    @Secured({Authority.ADMIN})
    @PostMapping("/startPeer")
    @ApiOperation("启动Peer节点")
    public void startPeer(@Valid @RequestBody PeerStartRequest request) throws Exception {
        peerService.startPeer(request);
    }

    @Secured({Authority.ADMIN})
    @PostMapping("/stopPeer")
    @ApiOperation("关闭Peer节点")
    public void stopPeer(@Valid @RequestBody BasePeerRequest request) throws Exception {
        peerService.stopPeer(request.getPeerName());
    }

    @Secured({Authority.ADMIN})
    @PostMapping("/getPeerStatus")
    @ApiOperation("获取Peer状态")
    public UniqueResult<PodStatus> getPeerStatus(@Valid @RequestBody BasePeerRequest request) throws Exception {
        return new UniqueResult<>(kubernetesService.getPeerStatus(request.getPeerName()));
    }

    @Secured({Authority.ADMIN})
    @PostMapping("/queryPeersInCluster")
    @ApiOperation("获取组织在集群里所有的Peer节点")
    public PageResult<PeerEntity> queryPeersInCluster(@Valid @RequestBody PaginationQueryRequest request) {
        return peerService.queryPeersInCluster(request.getPage(), request.getPageSize());
    }

    @Secured({Authority.ADMIN})
    @PostMapping("/queryPeersInChannel")
    @ApiOperation("获取通道中所有的Peer节点")
    public ListResult<ChannelPeer> queryPeersInChannel(@Valid @RequestBody BaseChannelRequest request) throws Exception {
        List<ChannelPeer> peers = ttpChannelApi.queryPeers(request.getChannelName());
        return new ListResult<>(peers);
    }
}
