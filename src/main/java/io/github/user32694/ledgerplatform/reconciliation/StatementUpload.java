package io.github.user32694.ledgerplatform.reconciliation;

/** 渠道账单导入请求；byte[] 通过防御性复制避免调用方修改上传内容。 */
public record StatementUpload(String channelCode, String fileName, byte[] content, String operator) {
    public StatementUpload {
        content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
        return content == null ? null : content.clone();
    }
}
