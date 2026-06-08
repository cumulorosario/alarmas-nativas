{
  "ruleChain": {
    "name": "Vigia FCM Notifications",
    "type": "CORE",
    "firstRuleNodeId": null,
    "root": false,
    "debugMode": true,
    "additionalInfo": {
      "description": "Multi-tenant FCM push. Compatible ThingsBoard CE."
    }
  },
  "metadata": {
    "firstNodeIndex": 0,
    "nodes": [
      {
        "type": "org.thingsboard.rule.engine.filter.TbMsgTypeSwitchNode",
        "name": "Tipo de Mensaje",
        "debugMode": true,
        "configuration": {"version": 0},
        "additionalInfo": {"layoutX": 100, "layoutY": 200}
      },
      {
        "type": "org.thingsboard.rule.engine.rest.TbRestApiCallNode",
        "name": "Enviar a FCM Webhook",
        "debugMode": true,
        "configuration": {
          "restEndpointUrlPattern": "http://fcm_webhook:5555/alarm",
          "requestMethod": "POST",
          "useSimpleClientHttpFactory": false,
          "readTimeoutMs": 15000,
          "maxParallelRequestsCount": 0,
          "useRedirectStrategy": false,
          "headers": {"Content-Type": "application/json"},
          "body": "{\n  \"secret\": \"vigia2024secret\",\n  \"tenantId\": \"${metadata.tenantId}\",\n  \"alarmId\": \"${msg.id.id}\",\n  \"type\": \"${msg.type}\",\n  \"severity\": \"${msg.severity}\",\n  \"status\": \"${msg.status}\",\n  \"originatorName\": \"${metadata.originatorName}\",\n  \"createdTime\": ${msg.createdTime}\n}",
          "credentials": {"type": "anonymous"}
        },
        "additionalInfo": {"layoutX": 400, "layoutY": 200}
      },
      {
        "type": "org.thingsboard.rule.engine.action.TbLogNode",
        "name": "Log Exito",
        "debugMode": true,
        "configuration": {
          "jsScript": "return 'FCM OK — tenant: ' + metadata.tenantId + ' | tipo: ' + msg.type + ' | dispositivo: ' + metadata.originatorName;"
        },
        "additionalInfo": {"layoutX": 700, "layoutY": 100}
      },
      {
        "type": "org.thingsboard.rule.engine.action.TbLogNode",
        "name": "Log Error",
        "debugMode": true,
        "configuration": {
          "jsScript": "return 'Error FCM — tenant: ' + metadata.tenantId + ' | tipo: ' + msg.type + ' | error: ' + metadata.error;"
        },
        "additionalInfo": {"layoutX": 700, "layoutY": 300}
      }
    ],
    "connections": [
      {"fromIndex": 0, "toIndex": 1, "type": "Alarm Created"},
      {"fromIndex": 0, "toIndex": 1, "type": "Alarm Severity Updated"},
      {"fromIndex": 0, "toIndex": 1, "type": "Alarm Updated"},
      {"fromIndex": 0, "toIndex": 1, "type": "Other"},
      {"fromIndex": 1, "toIndex": 2, "type": "Success"},
      {"fromIndex": 1, "toIndex": 3, "type": "Failure"}
    ],
    "ruleChainConnections": null
  }
}
