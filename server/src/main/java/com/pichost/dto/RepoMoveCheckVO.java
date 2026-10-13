package com.pichost.dto;

import lombok.Data;

/**
 * 仓库目录整理的自检项。
 */
@Data
public class RepoMoveCheckVO {

    private String name;
    private boolean ok;
    private String detail;

    public static RepoMoveCheckVO of(String name, boolean ok, String detail) {
        RepoMoveCheckVO vo = new RepoMoveCheckVO();
        vo.setName(name);
        vo.setOk(ok);
        vo.setDetail(detail);
        return vo;
    }
}
