package com.anhui.fabricbaasorg.bean;

import com.anhui.fabricbaascommon.bean.Node;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@ApiModel(value = "网络中的Orderer节点")
public class NetworkOrderer extends Node {
    @ApiModelProperty(value = "所属组织名称")
    private String organizationName;
}
