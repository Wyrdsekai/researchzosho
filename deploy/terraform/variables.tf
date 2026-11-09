variable "region" {
  description = "The AWS region."
  type        = string
  default     = "us-east-1"
}

variable "name" {
  description = "A name for this node's resources."
  type        = string
  default     = "researchzosho"
}

variable "instance_type" {
  description = "The VM. Four cores and sixteen gigabytes carry a handful of libraries with the model elsewhere."
  type        = string
  default     = "t3.xlarge"
}

variable "key_name" {
  description = "The ssh key pair's name in AWS, for the operator."
  type        = string
}

variable "ssh_from" {
  description = "Where ssh may come from."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "libraries" {
  description = "The libraries this node holds: one disk each, one container each, each at <name>.<domain>."
  type        = list(string)
  default     = ["alpha"]
}

variable "disk_gb" {
  description = "Each library's disk, in gigabytes."
  type        = number
  default     = 50
}

variable "domain" {
  description = "The domain the libraries answer under: alpha.<domain>. Point *.<domain> at the node's address."
  type        = string
}
