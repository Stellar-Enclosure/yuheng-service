package top.egon.cola.component.yuheng.admin.openapi.service;

import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.yuheng.admin.application.domain.bo.GatewayApplicationBO;
import top.egon.cola.component.yuheng.admin.application.repository.GatewayApplicationRepository;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.openapi.client.GatewayOpenApiFetchException;
import top.egon.cola.component.yuheng.admin.openapi.domain.dto.GatewayOpenApiDocumentDTO;
import top.egon.cola.component.yuheng.admin.openapi.domain.dto.GatewayOpenApiSyncCandidateDTO;
import top.egon.cola.component.yuheng.admin.scope.domain.dto.GatewayScopeQueryDTO;
import top.egon.cola.component.yuheng.admin.scope.service.GatewayScopeService;
import top.egon.cola.component.yuheng.contract.reporting.openapi.GatewayOpenApiGroupManifestDTO;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Immutable CI/deployment approvals for exact document sources; never a service-instance registry. */
public final class GatewayOpenApiSourceInventory {
    private final long owner;
    private final GatewayPersistenceContextComponent context;
    private final GatewayApplicationRepository applications;
    private final GatewayScopeService scopes;
    private final List<SourceVO> entries;
    private final Clock clock;

    public GatewayOpenApiSourceInventory(GatewayPersistenceContextComponent context,
            GatewayApplicationRepository applications,GatewayScopeService scopes,List<SourceVO> entries,Clock clock) {
        this.context=Objects.requireNonNull(context);this.applications=Objects.requireNonNull(applications);
        this.scopes=Objects.requireNonNull(scopes);this.clock=Objects.requireNonNull(clock);
        Long tenant=context.installationTenantId();if(tenant==null||tenant<=0)throw invalid();owner=tenant;
        if(entries==null||entries.isEmpty()||entries.size()>256)throw invalid();
        Set<String> ids=new HashSet<>();Map<List<String>,SourceVO> builds=new HashMap<>();
        for(SourceVO source:entries){
            if(source==null||!ids.add(source.sourceId()))throw invalid();
            SourceVO prior=builds.putIfAbsent(List.of(source.gatewayApplicationId(),source.buildId()),source);
            if(prior!=null&&(!prior.groups().equals(source.groups())||!prior.artifactVersion().equals(source.artifactVersion())
                    ||!prior.scopeBindingId().equals(source.scopeBindingId())))throw invalid();
        }
        this.entries=List.copyOf(entries);
    }

    public List<ApprovedPublicationVO> sources(GatewayApplicationBO application){
        requireOwner();Objects.requireNonNull(application);
        List<ApprovedPublicationVO> result=new ArrayList<>();
        for(SourceVO source:entries){if(!source.gatewayApplicationId().equals(application.getId()))continue;
            requireCurrent(source,application);
            GatewayOpenApiGroupManifestDTO manifest=new GatewayOpenApiGroupManifestDTO(List.copyOf(source.groups().keySet()),
                    GatewayOpenApiGroupManifestDTO.PATH_TEMPLATE,source.resourceUri().toASCIIString(),source.artifactVersion(),source.buildId());
            result.add(new ApprovedPublicationVO(manifest,source.groups().keySet().stream().map(group->candidate(source,application,group)).toList()));
        }
        return List.copyOf(result);
    }

    public void authorize(GatewayOpenApiSyncCandidateDTO candidate){approved(candidate);}

    public GatewayOpenApiGroupManifestDTO manifest(GatewayOpenApiSyncCandidateDTO candidate){
        SourceVO source=approved(candidate);
        return new GatewayOpenApiGroupManifestDTO(List.copyOf(source.groups().keySet()),GatewayOpenApiGroupManifestDTO.PATH_TEMPLATE,
                source.resourceUri().toASCIIString(),source.artifactVersion(),source.buildId());
    }

    public void authorizeResource(URI resource,String scope){
        requireOwner();if(!"yuheng.openapi.read".equals(scope))throw invalid();
        for(SourceVO source:entries){if(source.resourceUri().equals(resource)){
            GatewayApplicationBO app=applications.findByIdAndDeletedFalse(source.gatewayApplicationId()).orElseThrow(GatewayOpenApiSourceInventory::invalid);
            requireCurrent(source,app);return;
        }}
        throw invalid();
    }

    /** Recomputes raw whole-document bytes before any validation, snapshot or catalog apply. */
    public void verifyDocument(GatewayOpenApiSyncCandidateDTO candidate,GatewayOpenApiDocumentDTO document){
        SourceVO source=approved(candidate);
        if(document==null||!candidate.equals(document.candidate()))throw digestMismatch();
        String actual=sha256(document.rawBytes());
        if(!actual.equals(document.documentSha256())||!actual.equals(source.groups().get(candidate.openapiGroup())))throw digestMismatch();
    }

    private SourceVO approved(GatewayOpenApiSyncCandidateDTO candidate){
        requireOwner();Objects.requireNonNull(candidate);
        SourceVO source=entries.stream().filter(entry->entry.sourceId().equals(candidate.instanceId())).findFirst().orElseThrow(GatewayOpenApiSourceInventory::invalid);
        GatewayApplicationBO app=applications.findByIdAndDeletedFalse(source.gatewayApplicationId()).orElseThrow(GatewayOpenApiSourceInventory::invalid);
        requireCurrent(source,app);
        if(!source.groups().containsKey(candidate.openapiGroup())||!candidate(source,app,candidate.openapiGroup()).equals(candidate))throw invalid();
        return source;
    }
    private void requireCurrent(SourceVO source,GatewayApplicationBO app){
        Instant now=clock.instant();if(now.isBefore(source.approvedAt())||!now.isBefore(source.validUntil()))throw invalid();
        var binding=scopes.requireEnabled(new GatewayScopeQueryDTO(app.getBizCode(),app.getNamespace(),app.getEnv(),app.getApplicationCode()));
        if(!source.scopeBindingId().equals(binding.bindingId()))throw invalid();
    }
    private void requireOwner(){if(!Objects.equals(context.installationTenantId(),owner)||!Objects.equals(EgonColaTenantIdProvider.currentTenantId(),owner))throw invalid();}
    private static GatewayOpenApiSyncCandidateDTO candidate(SourceVO source,GatewayApplicationBO app,String group){
        return new GatewayOpenApiSyncCandidateDTO(app.getId(),app.getBizCode(),app.getApplicationCode(),source.buildId(),source.artifactVersion(),group,
                source.providerServiceName(),source.providerGroup(),source.providerVersion(),source.sourceId(),
                source.providerServiceName()+"."+source.kubernetesNamespace()+".svc.cluster.local",source.port(),false,
                GatewayOpenApiGroupManifestDTO.PATH_TEMPLATE,source.resourceUri(),source.approvedAt(),source.validUntil(),false,true);
    }
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA256_UNAVAILABLE");}}
    private static GatewayOpenApiFetchException invalid(){return new GatewayOpenApiFetchException("YUHENG_OPENAPI_SOURCE_NOT_APPROVED",false,"OpenAPI source approval is unavailable");}
    private static GatewayOpenApiFetchException digestMismatch(){return new GatewayOpenApiFetchException("YUHENG_OPENAPI_SOURCE_DIGEST_MISMATCH",false,"OpenAPI source document does not match its approved digest");}
    public record ApprovedPublicationVO(GatewayOpenApiGroupManifestDTO manifest,List<GatewayOpenApiSyncCandidateDTO> candidates){
        public ApprovedPublicationVO{candidates=List.copyOf(candidates);}
    }
    public record SourceVO(String sourceId,String scopeBindingId,String gatewayApplicationId,String clusterId,String kubernetesNamespace,
            String buildId,String artifactVersion,String providerServiceName,String providerGroup,String providerVersion,int port,
            URI resourceUri,Map<String,String> groups,Instant approvedAt,Instant validUntil){
        public SourceVO{
            for(String value:List.of(sourceId,scopeBindingId,gatewayApplicationId,clusterId,buildId,artifactVersion,providerGroup,providerVersion)){
                if(value.isBlank()||!value.equals(value.trim())||value.length()>256||value.codePoints().anyMatch(Character::isISOControl))throw invalid();}
            if(!providerServiceName.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
                    ||!kubernetesNamespace.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")||port<1||port>65535
                    ||resourceUri==null||!"https".equals(resourceUri.getScheme())||resourceUri.getHost()==null||resourceUri.getUserInfo()!=null
                    ||resourceUri.getRawQuery()!=null||resourceUri.getRawFragment()!=null||!resourceUri.equals(resourceUri.normalize())
                    ||groups==null||groups.isEmpty()||groups.size()>64||approvedAt==null||validUntil==null||!validUntil.isAfter(approvedAt))throw invalid();
            TreeMap<String,String> sorted=new TreeMap<>();groups.forEach((group,digest)->{
                if(group==null||!group.matches(GatewayOpenApiGroupManifestDTO.GROUP_PATTERN)||digest==null||!digest.matches("[0-9a-f]{64}"))throw invalid();sorted.put(group,digest);});
            groups=Collections.unmodifiableMap(sorted);
        }
    }
}
