{{- define "jhanvi-trends.labels" -}}
app.kubernetes.io/part-of: jhanvi-trends
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "jhanvi-trends.selector" -}}
app.kubernetes.io/name: {{ . }}
app.kubernetes.io/part-of: jhanvi-trends
{{- end -}}

{{/* image: <registry>/<prefix><name>:<tag> */}}
{{- define "jhanvi-trends.image" -}}
{{- $g := .root.Values.global -}}
{{- printf "%s/%s%s:%s" $g.imageRegistry $g.imagePrefix .name (.tag | default "latest") -}}
{{- end -}}

{{- define "jhanvi-trends.pullSecrets" -}}
{{- with .Values.global.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}

{{/* OpenTelemetry SDK settings shared by every service. Spans go to the
     node-local OpenTelemetry Collector (DaemonSet, hostPort 4317). */}}
{{- define "jhanvi-trends.otelEnv" -}}
- name: HOST_IP
  valueFrom: { fieldRef: { fieldPath: status.hostIP } }
- name: POD_NAME
  valueFrom: { fieldRef: { fieldPath: metadata.name } }
- name: POD_NAMESPACE
  valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
- name: OTEL_SERVICE_NAME
  value: {{ .name | quote }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: "http://$(HOST_IP):4317"
- name: OTEL_EXPORTER_OTLP_PROTOCOL
  value: grpc
- name: OTEL_EXPORTER_OTLP_INSECURE
  value: "true"
- name: OTEL_RESOURCE_ATTRIBUTES
  value: "service.namespace=jhanvi-trends,deployment.environment.name={{ .root.Values.global.environment }},k8s.namespace.name=$(POD_NAMESPACE),k8s.pod.name=$(POD_NAME)"
- name: OTEL_TRACES_SAMPLER
  value: parentbased_traceidratio
- name: OTEL_TRACES_SAMPLER_ARG
  value: {{ .root.Values.global.tracesSampleRatio | quote }}
# Metrics are scraped by Prometheus and logs are read from stdout, so only traces go over OTLP.
- name: OTEL_METRICS_EXPORTER
  value: none
- name: OTEL_LOGS_EXPORTER
  value: none
- name: OTEL_NODE_DISABLED_INSTRUMENTATIONS
  value: "fs,dns,net"
- name: OTEL_NODE_RESOURCE_DETECTORS
  value: "env,host,os,process,container"
{{- end -}}
