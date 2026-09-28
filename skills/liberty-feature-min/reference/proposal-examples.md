# 修正案の例

liberty-feature-min が案 A / B / C を示すときの書き方の例。

以下は、必要な feature の一覧が `restfulWS-3.1` / `cdi-4.0` / `jsonb-3.0` / `mpConfig-3.1` だった場合の例。実際の案は、分析結果に基づいて作る。

## Before

```xml
<featureManager>
    <feature>jakartaee-10.0</feature>
    <feature>microProfile-7.0</feature>
    <feature>servlet-6.0</feature>   <!-- jakartaee-10.0 に含まれるので重複 -->
    <feature>cdi-4.0</feature>       <!-- jakartaee-10.0 に含まれるので重複 -->
</featureManager>
```

## After案A（安全：重複だけを削除）

集約 feature はそのまま残し、集約 feature に含まれる重複した指定だけを削除する。

```xml
<featureManager>
    <feature>jakartaee-10.0</feature>
    <feature>microProfile-7.0</feature>
</featureManager>
```

## After案B（最小：必要な feature の一覧に基づいて分解）

```xml
<featureManager>
    <feature>restfulWS-3.1</feature>
    <feature>cdi-4.0</feature>
    <feature>jsonb-3.0</feature>
    <feature>mpConfig-3.1</feature>
</featureManager>
```

## After案C（versionless）

```xml
<featureManager>
    <platform>jakartaee-10.0</platform>
    <platform>microProfile-7.0</platform>
    <feature>restfulWS</feature>
    <feature>cdi</feature>
    <feature>jsonb</feature>
    <feature>mpConfig</feature>
</featureManager>
```
