# 01 — Introduction and Goals

## What Agora is

Agora is a broker- and provider-agnostic **MCP tool suite** for market data, quant
research, and trade execution. It exposes a catalog of financial tools over the Model
Context Protocol (Streamable HTTP transport). The guiding principle: a tool, provider,
broker, or quant building block is registered **once in Agora**, and every consumer can
use it without a rebuild.

## Quality goals

| Goal | Motivation |
|---|---|
| **Neutrality** | The API carries no consumer/investment domain vocabulary, so a second consumer validates the abstraction. |
| **Extensibility** | New providers/brokers/tools plug in via ordered plugin chains and bean discovery — no consumer changes. |
| **Safety of execution** | Trading is gated by explicit connection selection plus bearer-token authorization; mutating ops require stronger authorization than reads. |
| **Operational simplicity** | One Docker image, one health endpoint, config via environment. |

## Stakeholders

| Role | Interest |
|---|---|
| Consumer services (first: Dracul) | Stable, neutral tool contracts. |
| Operators | Predictable deploy, health, and connection diagnostics. |
| Contributors | Clear plugin extension points and scope boundary. |
