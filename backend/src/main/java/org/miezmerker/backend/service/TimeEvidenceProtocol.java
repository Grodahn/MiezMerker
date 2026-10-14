package org.miezmerker.backend.service;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import org.miezmerker.backend.crypto.*;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/** Strict, bounded adapter for finalized #128 metadata and #129 signing bytes. */
public final class TimeEvidenceProtocol {
    private TimeEvidenceProtocol() {}
    public static final BigInteger U64_MAX=BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE, requiredProperties={"nodeId","incarnation","bootCounter","resetReason","startedUptimeMs","firstPossibleSequence","formatVersion"})
    public record Boot(String nodeId,String incarnation,long bootCounter,long resetReason,
            @Schema(implementation=Long.class,type="integer",format="uint64") BigInteger startedUptimeMs,
            @Schema(implementation=Long.class,type="integer",format="uint64") BigInteger firstPossibleSequence,int formatVersion) {}
    @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE, requiredProperties={"organizationId","boot","challenge","proof","acceptance","challengeBytes","acceptanceBytes"})
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Request(String organizationId,Boot boot,NodeTimeProtocol.Challenge challenge,String proof,
            NodeTimeProtocol.Acceptance acceptance,String challengeBytes,String acceptanceBytes,
            @Schema(description="Exact firmware metadata JSON, retained as collector-reported audit data; never an uncertainty/trust authority",maxLength=1024) String nodeMetadata) {
        public Request(String organizationId,Boot boot,NodeTimeProtocol.Challenge challenge,String proof,
                NodeTimeProtocol.Acceptance acceptance,String challengeBytes,String acceptanceBytes) {
            this(organizationId,boot,challenge,proof,acceptance,challengeBytes,acceptanceBytes,null);
        }
    }
    @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"organizationId","boot"})
    public record BootRequest(String organizationId,Boot boot) {}
    public static BootRequest parseBoot(byte[] body) {
        var n=NodeTimeProtocol.json(body); NodeTimeProtocol.fields(n,"organizationId","boot");
        String org=NodeTimeProtocol.text(n,"organizationId"); NodeTimeProtocol.uuid(org);
        return new BootRequest(org,parseBootNode(n.path("boot")));
    }
    private static Boot parseBootNode(JsonNode b) {
        NodeTimeProtocol.fields(b,"nodeId","incarnation","bootCounter","resetReason","startedUptimeMs","firstPossibleSequence","formatVersion");
        var result=new Boot(NodeTimeProtocol.text(b,"nodeId"),NodeTimeProtocol.text(b,"incarnation"),u32(b,"bootCounter"),u32(b,"resetReason"),integer(b,"startedUptimeMs",U64_MAX),integer(b,"firstPossibleSequence",U64_MAX),integer(b,"formatVersion",BigInteger.valueOf(65535)).intValueExact());
        validateBoot(result); return result;
    }
    public static void validateBoot(Boot b) {
        if(b==null || b.formatVersion()!=1 || b.bootCounter()<1 || b.bootCounter()>0xffff_ffffL || b.resetReason()<0 || b.resetReason()>0xffff_ffffL
                || b.startedUptimeMs()==null || b.startedUptimeMs().signum()<0 || b.startedUptimeMs().compareTo(U64_MAX)>0
                || b.firstPossibleSequence()==null || b.firstPossibleSequence().signum()<=0 || b.firstPossibleSequence().compareTo(U64_MAX)>0)
            throw new IllegalArgumentException("Invalid boot");
        NodeTimeProtocol.uuid(b.nodeId()); NodeTimeProtocol.uuid(b.incarnation());
    }
    static BigInteger integer(JsonNode n,String f,BigInteger max) {
        var v=n.path(f);
        if(!v.isIntegralNumber() || v.bigIntegerValue().signum()<0 || v.bigIntegerValue().compareTo(max)>0)
            throw new IllegalArgumentException("Invalid integer");
        return v.bigIntegerValue();
    }
    static long u32(JsonNode n,String f) { return integer(n,f,BigInteger.valueOf(0xffff_ffffL)).longValueExact(); }
    public static Request parse(byte[] body) {
        var n=NodeTimeProtocol.json(body);
        if(n.has("nodeMetadata")) NodeTimeProtocol.fields(n,"organizationId","boot","challenge","proof","acceptance","challengeBytes","acceptanceBytes","nodeMetadata");
        else NodeTimeProtocol.fields(n,"organizationId","boot","challenge","proof","acceptance","challengeBytes","acceptanceBytes");
        String org=NodeTimeProtocol.text(n,"organizationId"); NodeTimeProtocol.uuid(org);
        var b=n.path("boot");
        NodeTimeProtocol.fields(b,"nodeId","incarnation","bootCounter","resetReason","startedUptimeMs","firstPossibleSequence","formatVersion");
        var boot=new Boot(NodeTimeProtocol.text(b,"nodeId"),NodeTimeProtocol.text(b,"incarnation"),u32(b,"bootCounter"),
            u32(b,"resetReason"),integer(b,"startedUptimeMs",U64_MAX),integer(b,"firstPossibleSequence",U64_MAX),
            integer(b,"formatVersion",BigInteger.valueOf(65535)).intValueExact());
        // Reuse strict challenge parser instead of a second permissive Jackson binding.
        String wrapper="{\"organizationId\":\""+org+"\",\"registrationState\":\"CLAIMED\",\"challenge\":"+n.path("challenge")+"}";
        var c=NodeTimeProtocol.parseRequest(wrapper.getBytes(java.nio.charset.StandardCharsets.UTF_8)).challenge();
        var a=n.path("acceptance");
        NodeTimeProtocol.fields(a,"nodeId","incarnation","bootCounter","proofDigest","challengeNonceDigest","acceptedUptimeMs","anchorId","signature","version");
        var accept=new NodeTimeProtocol.Acceptance(NodeTimeProtocol.text(a,"nodeId"),NodeTimeProtocol.text(a,"incarnation"),u32(a,"bootCounter"),
            NodeTimeProtocol.text(a,"proofDigest"),NodeTimeProtocol.text(a,"challengeNonceDigest"),integer(a,"acceptedUptimeMs",U64_MAX),
            NodeTimeProtocol.text(a,"anchorId"),NodeTimeProtocol.text(a,"signature"),integer(a,"version",BigInteger.valueOf(65535)).intValueExact());
        var r=new Request(org,boot,c,NodeTimeProtocol.text(n,"proof"),accept,
                NodeTimeProtocol.text(n,"challengeBytes"),NodeTimeProtocol.text(n,"acceptanceBytes"),
                n.has("nodeMetadata")?NodeTimeProtocol.text(n,"nodeMetadata"):null);
        validate(r); return r;
    }
    public static String signedBytes(byte[] message,String signature) {
        return EcKeyUtils.b64u(ByteBuffer.allocate(message.length+64).put(message).put(NodeTimeProtocol.decode(signature,64)).array());
    }
    public static void validate(Request r) {
        NodeTimeProtocol.uuid(r.organizationId()); NodeTimeProtocol.validate(r.challenge());
        var b=r.boot(); var c=r.challenge(); var a=r.acceptance();
        if(b == null || b.formatVersion()!=1 || b.bootCounter()<1 || b.bootCounter()>0xffff_ffffL
                || b.resetReason()<0 || b.resetReason()>0xffff_ffffL
                || b.startedUptimeMs()==null || b.startedUptimeMs().signum()<0 || b.startedUptimeMs().compareTo(U64_MAX)>0
                || b.firstPossibleSequence()==null || b.firstPossibleSequence().signum()<=0 || b.firstPossibleSequence().compareTo(U64_MAX)>0
                || !c.nodeId().equals(b.nodeId()) || !c.incarnation().equals(b.incarnation()) || c.bootCounter()!=b.bootCounter()
                || b.startedUptimeMs().compareTo(c.challengeUptimeMs())>0
                || r.proof()==null || r.proof().length()>2048 || a==null)
            throw new IllegalArgumentException("Invalid evidence");
        NodeTimeProtocol.uuid(b.nodeId()); NodeTimeProtocol.uuid(b.incarnation());
        if(r.nodeMetadata()!=null) {
            if(r.nodeMetadata().length()>1024) throw new IllegalArgumentException("Metadata too large");
            var m=NodeTimeProtocol.json(r.nodeMetadata().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            NodeTimeProtocol.fields(m,"anchorId","formatVersion","verifiedSecurityTime","proofIdOrDigest","uptimeMs","utcMs","source","uncertaintyMinusMs","uncertaintyPlusMs","driftPpmBound");
            if(!NodeTimeProtocol.text(m,"anchorId").equals(a.anchorId()) || integer(m,"formatVersion",BigInteger.ONE).intValueExact()!=1
                    || !m.path("verifiedSecurityTime").isBoolean() || !m.path("verifiedSecurityTime").asBoolean()
                    || integer(m,"source",BigInteger.valueOf(2)).intValueExact()!=0
                    || !integer(m,"uptimeMs",U64_MAX).equals(a.acceptedUptimeMs())) throw new IllegalArgumentException("Metadata binding");
            NodeTimeProtocol.uuid(NodeTimeProtocol.text(m,"proofIdOrDigest"));
            integer(m,"utcMs",BigInteger.valueOf(Long.MAX_VALUE));
            integer(m,"uncertaintyMinusMs",U64_MAX); integer(m,"uncertaintyPlusMs",U64_MAX); u32(m,"driftPpmBound");
        }
        byte[] accepted=NodeTimeProtocol.acceptanceBytes(c,r.proof(),a);
        // ADR 0017 provisional maximum 30 seconds; narrowing is allowed in #130.
        if(a.acceptedUptimeMs().subtract(c.challengeUptimeMs()).compareTo(BigInteger.valueOf(30_000))>0
                || !signedBytes(NodeTimeProtocol.challengeBytes(c),c.deviceChallengeSignature()).equals(r.challengeBytes())
                || !signedBytes(accepted,a.signature()).equals(r.acceptanceBytes()))
            throw new IllegalArgumentException("Invalid acceptance bytes or lifetime");
    }
}

