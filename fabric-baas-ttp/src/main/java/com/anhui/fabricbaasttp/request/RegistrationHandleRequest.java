package com.anhui.fabricbaasttp.request;

import com.anhui.fabricbaascommon.request.BaseOrganizationRequest;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@ApiModel(value = "注册处理请求")
public class RegistrationHandleRequest extends BaseOrganizationRequest {
    @ApiModelProperty(value = "是否通过注册申请", required = true)
    private boolean isAllowed;
}